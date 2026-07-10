package com.amar.vault

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amar.vault.indexing.DiscoveryEngine
import com.amar.vault.indexing.DiscoverySpec
import com.amar.vault.indexing.FixedBurstBatchPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Background worker that bulk-indexes photos from user-selected folders.
 *
 * Features:
 * - Thermal-aware batching: burst 8 photos → pause 2s → repeat
 * - Persistent notification with progress
 * - Resumable: tracks last-processed timestamp, skips already-indexed
 * - WorkManager guarantees completion even if app is killed
 */
@HiltWorker
class BulkScanWorker @dagger.assisted.AssistedInject constructor(
    @dagger.assisted.Assisted context: Context,
    @dagger.assisted.Assisted params: WorkerParameters,
    private val engine: DiscoveryEngine,
    private val pipeline: IndexingPipeline,
    private val health: IndexHealthState,
    private val vectorSearchManager: VectorSearchManager,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "BulkScanWorker"
        private const val CHANNEL_ID = "bulk_scan"
        private const val NOTIFICATION_ID = 9001
        // Burst size + cooldown are centralized in VaultConfig.Indexing (same values).

        /** Enqueue a bulk scan. Safe to call multiple times — WorkManager deduplicates. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BulkScanWorker>()
                .addTag("bulk_scan")
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("bulk_scan", androidx.work.ExistingWorkPolicy.KEEP, request)
        }

        /** Check if a bulk scan is currently running. */
        fun isRunning(context: Context): Boolean {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosByTag("bulk_scan").get()
            return infos.any { it.state == WorkInfo.State.RUNNING }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        return buildForegroundInfo(0, 0, 0)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            createNotificationChannel()
            health.beginIndexingSession()
            val prefs = ScanPreferences.prefsFlow(applicationContext).first()
            val folders = prefs.foldersToScan()

            // Bulk scope: all volumes, user-selected folders, and NO Room dedup — the pipeline's own
            // pHash check is the bulk path's intentional dedup. Identical query to the old queryPhotos().
            val spec = DiscoverySpec(
                mediaScope = DiscoverySpec.MediaScope.ALL_VOLUMES,
                folders = folders,
                sinceEpochSeconds = null,
                dedupAgainstIndexed = false,
            )
            val uris = engine.discover(spec)

            Log.d(TAG, "Starting bulk scan: ${uris.size} photos from ${folders.size} folders")

            if (uris.isEmpty()) {
                ScanPreferences.markInitialScanDone(applicationContext)
                health.endIndexingSession()
                return@withContext Result.success()
            }

            var indexed = 0
            var skipped = 0

            // Fixed 8-photo bursts with a 2s cool-down between them (skipped after the final burst).
            val outcome = engine.process(
                candidates = uris,
                policy = FixedBurstBatchPolicy(VaultConfig.Indexing.BATCH_SIZE, VaultConfig.Indexing.COOL_DOWN_MS),
                isActive = { !isStopped },
                indexOne = { i, total, photoUri ->
                    // Update notification
                    setForeground(buildForegroundInfo(i + 1, total, indexed))

                    try {
                        val stream = applicationContext.contentResolver.openInputStream(photoUri)
                        if (stream != null) {
                            val bitmap = BitmapFactory.decodeStream(stream)
                            stream.close()
                            if (bitmap != null) {
                                pipeline.indexBitmap(bitmap, photoUri.toString(), "photo")
                                bitmap.recycle()
                                indexed++
                            } else {
                                skipped++
                            }
                        } else {
                            skipped++
                        }
                    } catch (e: Exception) {
                        IndexMetrics.increment(IndexMetrics.Event.INDEX_FAILURE)
                        Log.w(TAG, "Failed to index $photoUri: ${e.message}")
                        skipped++
                    }

                    // Report progress
                    setProgress(workDataOf(
                        "current" to (i + 1),
                        "total" to total,
                        "indexed" to indexed,
                    ))
                },
            )

            // Cancelled mid-run → fail fast, skipping finalization (unchanged: was an early return
            // on isStopped, leaving the indexing session open for next-launch reconciliation).
            if (outcome.cancelled) return@withContext Result.failure()

            ScanPreferences.markInitialScanDone(applicationContext)
            // Durable checkpoint: persist the HNSW graph + id-mappings together so the
            // vectors indexed this run survive restart, consistent with each other.
            runCatching { vectorSearchManager.saveState() }
                .onFailure { VaultLog.w(TAG, "Vector saveState failed", it) }
            health.endIndexingSession()
            Log.d(TAG, "Bulk scan complete: $indexed indexed, $skipped skipped out of ${uris.size}")

            // Final notification
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Scan Complete")
                .setContentText("$indexed photos indexed")
                .setAutoCancel(true)
                .build())

            Result.success(workDataOf("indexed" to indexed, "total" to uris.size))
        } catch (e: Exception) {
            IndexMetrics.increment(IndexMetrics.Event.INDEX_RETRY)
            // Leave the indexing session marked open so reconciliation runs next launch.
            Log.e(TAG, "Bulk scan failed", e)
            Result.retry()
        }
    }

    private fun buildForegroundInfo(current: Int, total: Int, indexed: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Scanning Photos")
            .setContentText(if (total > 0) "$current of $total ($indexed indexed)" else "Preparing scan...")
            .setProgress(total, current, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        // Android 10+ (Q) requires explicit foreground service type
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Scanning",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows progress while scanning your photos" }
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}