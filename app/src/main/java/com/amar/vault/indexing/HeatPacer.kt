package com.amar.vault.indexing

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.amar.vault.IndexMetrics
import com.amar.vault.VaultLog
import kotlinx.coroutines.delay

class HeatPacer(
    private val gauge: Gauge,
    private val maxPauseMs: Long,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    interface Gauge {
        fun headroom(forecastSeconds: Int): Float
        fun status(): Int
        fun lines(): Map<Int, Float> = emptyMap()
    }

    enum class Heat { COOL, HOT, UNKNOWN }

    private var readAt = 0L
    private var reading = Float.NaN
    private var everRead = false
    private val lines: Map<Int, Float> by lazy { gauge.lines().filterValues { !it.isNaN() } }

    suspend fun awaitCool(isActive: () -> Boolean = { true }): Long {
        val start = now()
        when (heat()) {
            Heat.COOL -> return 0
            Heat.UNKNOWN -> {
                sleep(BLIND_PAUSE_MS)
                return now() - start
            }
            Heat.HOT -> Unit
        }
        IndexMetrics.increment(IndexMetrics.Event.INDEX_HEAT_PAUSE)
        VaultLog.i(TAG, "warm (headroom ${reading}, status ${gauge.status()}): pausing")
        while (isActive() && now() - start < maxPauseMs) {
            sleep(minOf(STEP_MS, maxPauseMs - (now() - start)).coerceAtLeast(1))
            if (cooled()) break
        }
        val paused = now() - start
        IndexMetrics.recordDuration(IndexMetrics.Timing.INDEX_HEAT_PAUSED, paused)
        VaultLog.i(TAG, "paused ${paused} ms (headroom ${reading}, status ${gauge.status()})")
        return paused
    }

    fun heat(): Heat {
        val status = gauge.status()
        val forecast = headroom(FORECAST_SECONDS)
        if (status < 0 && forecast.isNaN()) return Heat.UNKNOWN
        if (status >= PowerManager.THERMAL_STATUS_MODERATE) return Heat.HOT
        if (!forecast.isNaN() && forecast >= hotLine()) return Heat.HOT
        return Heat.COOL
    }

    private fun cooled(): Boolean {
        if (gauge.status() > PowerManager.THERMAL_STATUS_LIGHT) return false
        val now = headroom(0)
        return now.isNaN() || now < coolLine()
    }

    private fun hotLine(): Float = lines[PowerManager.THERMAL_STATUS_MODERATE] ?: SEVERE_LINE

    private fun coolLine(): Float = lines[PowerManager.THERMAL_STATUS_LIGHT] ?: COOL_LINE

    private fun headroom(forecastSeconds: Int): Float {
        val at = now()
        if (everRead && at - readAt < MIN_GAP_MS) return reading
        everRead = true
        readAt = at
        reading = gauge.headroom(forecastSeconds)
        return reading
    }

    class Phone(context: Context) : Gauge {
        private val power = context.getSystemService(PowerManager::class.java)

        override fun headroom(forecastSeconds: Int): Float =
            if (Build.VERSION.SDK_INT >= 30 && power != null) power.getThermalHeadroom(forecastSeconds) else Float.NaN

        override fun status(): Int =
            if (Build.VERSION.SDK_INT >= 29 && power != null) power.currentThermalStatus else -1

        override fun lines(): Map<Int, Float> =
            if (Build.VERSION.SDK_INT >= 35 && power != null) {
                runCatching { power.thermalHeadroomThresholds }.getOrNull().orEmpty()
            } else emptyMap()
    }

    companion object {
        const val SEVERE_LINE = 1.0f
        const val COOL_LINE = 0.9f
        const val FORECAST_SECONDS = 10
        const val STEP_MS = 5_000L
        const val MIN_GAP_MS = 2_000L
        const val BLIND_PAUSE_MS = 250L
        const val WHILE_SCANNING_MS = 10_000L
        const val IN_THE_BACKGROUND_MS = 60_000L
        private const val TAG = "HeatPacer"

        fun of(context: Context, maxPauseMs: Long): HeatPacer = HeatPacer(Phone(context.applicationContext), maxPauseMs)
    }
}
