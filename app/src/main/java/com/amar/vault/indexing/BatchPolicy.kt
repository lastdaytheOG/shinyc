package com.amar.vault.indexing

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Pacing strategy — **pacing only**. Determines how many candidates to process before pausing and
 * how long to pause. It owns no scheduling, no checkpointing, no decode, no pipeline access.
 *
 * Each trigger supplies its own implementation; [DiscoveryEngine] executes it blindly with zero
 * trigger branching. Genuine polymorphism (two real, non-interchangeable pacing behaviours), so an
 * interface is justified per the standing rule.
 */
interface BatchPolicy {
    /** Size of the next batch given how many candidates remain. The engine caps this at [remaining]. */
    fun nextBatchSize(remaining: Int): Int

    /** Delay to apply after a batch completes. [isFinalBatch] lets a policy skip the trailing pause. */
    fun postBatchDelayMs(isFinalBatch: Boolean): Long
}

/**
 * Fixed-size bursts with an unconditional cool-down between them — the bulk path's pacing.
 *
 * Verbatim behaviour of `BulkScanWorker`'s `(i+1) % BATCH_SIZE == 0 && i < size-1 → delay`: a
 * constant [size] burst, a constant [delayMs] pause after every burst **except the final one**.
 */
class FixedBurstBatchPolicy(
    private val size: Int,
    private val delayMs: Long,
) : BatchPolicy {
    override fun nextBatchSize(remaining: Int): Int = size
    override fun postBatchDelayMs(isFinalBatch: Boolean): Long = if (isFinalBatch) 0L else delayMs
}

/**
 * Thermal- and memory-adaptive pacing — the foreground and nightly paths' shared strategy.
 *
 * Verbatim behaviour of `ThermalAwareBatchProcessor` / `IndexingForegroundService`: batch size
 * scales down under thermal pressure or low memory (10/50/30/200), and an exponential backoff
 * (`2000 · 2^min(thermal,4)`) is applied after every batch — including the final one — whenever
 * thermal ≥ MODERATE(3). Thermal/memory are read fresh each call, exactly as before.
 */
class ThermalAdaptiveBatchPolicy(context: Context) : BatchPolicy {

    private val thermalManager = context.getSystemService(PowerManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    override fun nextBatchSize(remaining: Int): Int = when {
        thermalStatus() >= THERMAL_STATUS_SEVERE   -> 10   // hot — go slow
        thermalStatus() >= THERMAL_STATUS_MODERATE -> 50   // warm
        freeMemoryMb() < 200                       -> 30   // low RAM
        else                                       -> 200  // cool + charging = full speed
    }

    override fun postBatchDelayMs(isFinalBatch: Boolean): Long {
        val thermal = thermalStatus()
        // Exponential backoff if thermal pressure rises (applies after every batch, as before).
        return if (thermal >= THERMAL_STATUS_MODERATE) 2000L * (1 shl thermal.coerceAtMost(4)) else 0L
    }

    private fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) thermalManager?.currentThermalStatus ?: 0 else 0

    private fun freeMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    companion object {
        private const val THERMAL_STATUS_MODERATE = 3
        private const val THERMAL_STATUS_SEVERE   = 5
    }
}
