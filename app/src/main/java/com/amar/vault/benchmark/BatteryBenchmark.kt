package com.amar.vault.benchmark

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Module 10 — Battery benchmark.
 *
 * Android exposes no per-app energy meter, so this module measures what the platform
 * actually provides: device charge-counter deltas (µAh) across a labeled session,
 * plus session wall-clock duration. That is a DEVICE-level delta — it includes
 * everything running on the phone — so results are only meaningful for long sessions
 * on an otherwise idle, unplugged device. The report says so on every metric; when
 * the device is charging the delta is reported as unmeasurable (charging invalidates
 * the counter direction).
 *
 * Usage: [session] wraps any workload (the orchestrator wraps whole suite runs; the
 * dashboard can wrap OCR / indexing / retrieval / LLM sessions the same way).
 * Worker durations come from the observed [com.amar.vault.IndexMetrics] timings in
 * the indexing section — not re-measured here.
 */
class BatteryBenchmark(private val context: Context) {

    data class SessionResult(
        val label: String,
        val durationMs: Long,
        val chargeDeltaUah: Long?,   // negative = drain; null = unmeasurable
        val capacityDeltaPercent: Int?,
        val wasCharging: Boolean,
    )

    private val batteryManager: BatteryManager?
        get() = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun chargeCounterUah(): Long? =
        batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.takeIf { it != Long.MIN_VALUE && it != 0L }

    private fun capacityPercent(): Int? =
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }

    /** Measure a workload as a labeled battery session. */
    suspend fun <T> session(label: String, block: suspend () -> T): Pair<T, SessionResult> {
        val charging = isCharging()
        val startCharge = chargeCounterUah()
        val startCap = capacityPercent()
        val t0 = System.currentTimeMillis()
        val result = block()
        val duration = System.currentTimeMillis() - t0
        val endCharge = chargeCounterUah()
        val endCap = capacityPercent()
        return result to SessionResult(
            label = label,
            durationMs = duration,
            chargeDeltaUah = if (!charging && startCharge != null && endCharge != null) endCharge - startCharge else null,
            capacityDeltaPercent = if (!charging && startCap != null && endCap != null) endCap - startCap else null,
            wasCharging = charging,
        )
    }

    fun toSection(sessions: List<SessionResult>): BenchmarkSection {
        val rows = sessions.map { s ->
            mapOf(
                "session" to s.label,
                "durationMs" to s.durationMs.toString(),
                "chargeDeltaUah" to (s.chargeDeltaUah?.toString() ?: "unmeasurable"),
                "capacityDeltaPercent" to (s.capacityDeltaPercent?.toString() ?: "unmeasurable"),
                "wasCharging" to s.wasCharging.toString(),
            )
        }
        val measurable = sessions.filter { it.chargeDeltaUah != null }
        val chargingNote = when {
            sessions.isEmpty() -> "no sessions recorded in this run"
            measurable.isEmpty() -> "unmeasurable: device charging or charge counter unsupported"
            else -> "device-level delta over ${measurable.size} unplugged session(s) — meaningful only on an otherwise idle device"
        }
        return BenchmarkSection(
            id = "battery",
            title = "Battery (device charge-counter deltas per labeled session)",
            metrics = listOf(
                MetricValue("sessions.count", sessions.size.toDouble(), "count", true),
                MetricValue("charge.deltaUah.total",
                    measurable.takeIf { it.isNotEmpty() }?.sumOf { it.chargeDeltaUah!!.toDouble() },
                    "µAh", true, chargingNote),
                MetricValue("sessions.durationMs.total",
                    sessions.takeIf { it.isNotEmpty() }?.sumOf { it.durationMs.toDouble() },
                    "ms", false, if (sessions.isEmpty()) "no sessions recorded" else ""),
            ),
            rows = rows,
        )
    }
}
