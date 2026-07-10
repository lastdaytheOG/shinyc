package com.amar.vault

import android.content.Context
import android.graphics.Bitmap
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.amar.vault.indexing.BatchPolicy
import com.amar.vault.indexing.BitmapDecoding
import com.amar.vault.indexing.DiscoveryEngine
import com.amar.vault.indexing.DiscoverySpec
import com.amar.vault.indexing.ThermalAdaptiveBatchPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

@HiltWorker
class NightlyIndexWorker @dagger.assisted.AssistedInject constructor(
    @dagger.assisted.Assisted ctx: Context,
    @dagger.assisted.Assisted params: WorkerParameters,
    private val engine: DiscoveryEngine,
    private val pipeline: IndexingPipeline,
    private val vectorSearchManager: VectorSearchManager,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            val cancelSignal = coroutineContext
            try {
                // Incremental scope: primary external, no folder filter, only images added since the
                // last run (MediaStore DATE_ADDED is in seconds), deduped against Room — identical to
                // the previous findUnindexedImagesSince() query + getAll() dedup.
                val spec = DiscoverySpec(
                    mediaScope = DiscoverySpec.MediaScope.PRIMARY_EXTERNAL,
                    folders = emptyList(),
                    sinceEpochSeconds = getLastIndexedTime(applicationContext) / 1000,
                    dedupAgainstIndexed = true,
                )
                val candidates = engine.discover(spec)
                // Empty → success WITHOUT advancing the last-indexed timestamp (unchanged).
                if (candidates.isEmpty()) return@withContext Result.success()

                val policy: BatchPolicy = ThermalAdaptiveBatchPolicy(applicationContext)
                engine.process(
                    candidates = candidates,
                    policy = policy,
                    isActive = { cancelSignal.isActive },
                    indexOne = { _, _, uri ->
                        var bmp: Bitmap? = null
                        try {
                            bmp = BitmapDecoding.loadSoftware(applicationContext, uri, mutable = true)
                            if (bmp != null) pipeline.indexBitmap(bmp, uri.toString())
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bmp?.recycle(); bmp = null
                        }
                    },
                    onBatchCommitted = { _, _ ->
                        // Flush the vector search state (native graph + ID mappings) after every batch.
                        try { vectorSearchManager.saveState() } catch (e: Exception) { e.printStackTrace() }
                    },
                )

                // Save last indexed timestamp
                saveLastIndexedTime(applicationContext)
                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()
                Result.retry()
            }
        }
    }

    private fun getLastIndexedTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Default: 30 days ago on first run — index recent history only
        val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
        return prefs.getLong(KEY_LAST_INDEXED, thirtyDaysAgo)
    }

    private fun saveLastIndexedTime(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_INDEXED, System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val WORK_TAG        = "nightly_index"
        private const val PREFS_NAME       = "amar_prefs"
        private const val KEY_LAST_INDEXED = "last_indexed_timestamp"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NightlyIndexWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresCharging(true)
                        .setRequiresDeviceIdle(true)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(
                    calculateDelayTo2AM(),
                    TimeUnit.MILLISECONDS
                )
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }

        private fun calculateDelayTo2AM(): Long {
            val now  = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 2)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            return target.timeInMillis - now.timeInMillis
        }
    }
}
