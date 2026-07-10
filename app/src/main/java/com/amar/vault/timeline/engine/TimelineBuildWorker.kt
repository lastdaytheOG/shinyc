package com.amar.vault.timeline.engine

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.amar.vault.VaultDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TimelineBuildWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return buildMutex.withLock {
            try {
                // Prevent duplicate running jobs via mutex if somehow enqueued parallel
                // (ExistingWorkPolicy.REPLACE / KEEP usually handles enqueue coalescing)
                val db = VaultDatabase.get(applicationContext)
                
                // Fetch versions from upstream (mocked logic here)
                TimelineEngine.rebuildTimeline(
                    db = db,
                    relationshipVersion = "v1",
                    classificationVersion = "v1",
                    canonicalizationVersion = "v1",
                    eventVersion = "v1"
                )
                
                Result.success()
            } catch (e: Exception) {
                // FAILED handled intrinsically by the engine marking state
                Result.failure()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "TimelineBuildWorker"
        private val buildMutex = Mutex()

        fun enqueue(context: Context) {
            val workManager = WorkManager.getInstance(context)
            val request = OneTimeWorkRequestBuilder<TimelineBuildWorker>().build()
            
            // Rebuild Coalescing logic:
            // KEEP policy guarantees that if a build is already enqueued or running, 
            // the new request is ignored, preventing a Rebuild Storm.
            workManager.enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
