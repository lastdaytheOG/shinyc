package com.amar.vault.indexing

import android.app.ActivityManager
import android.content.Context

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

class FixedBatchPolicy(private val size: Int) : BatchPolicy {
    override fun nextBatchSize(remaining: Int): Int = size
    override fun postBatchDelayMs(isFinalBatch: Boolean): Long = 0L
}

class MemoryAwareBatchPolicy(context: Context) : BatchPolicy {
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    override fun nextBatchSize(remaining: Int): Int = if (freeMemoryMb() < LOW_MEMORY_MB) SMALL else LARGE
    override fun postBatchDelayMs(isFinalBatch: Boolean): Long = 0L

    private fun freeMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private companion object {
        const val LOW_MEMORY_MB = 200
        const val SMALL = 30
        const val LARGE = 200
    }
}
