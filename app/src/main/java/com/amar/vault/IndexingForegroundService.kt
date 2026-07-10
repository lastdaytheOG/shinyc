package com.amar.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.amar.vault.indexing.BitmapDecoding
import com.amar.vault.indexing.DiscoveryEngine
import com.amar.vault.indexing.DiscoverySpec
import com.amar.vault.indexing.ThermalAdaptiveBatchPolicy
import kotlinx.coroutines.*

class IndexingForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting vault indexing...", 0, 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INDEX_ALL -> {
                if (!isRunning) {
                    isRunning = true
                    indexAll()
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun indexAll() {
        val health = IndexHealthState(applicationContext)
        scope.launch {
            try {
                health.beginIndexingSession()
                updateNotification("Scanning your photos...", 0, 0)

                // Discovery (query + dedup) is owned by the shared engine; this service keeps only
                // its lifecycle, notifications, decode, and thermal-adaptive pacing (via the policy).
                val engine = DiscoveryEngine(applicationContext)
                val spec = DiscoverySpec(
                    mediaScope = DiscoverySpec.MediaScope.PRIMARY_EXTERNAL,
                    folders = emptyList(),
                    sinceEpochSeconds = null,
                    dedupAgainstIndexed = true,
                )
                val candidates = engine.discover(spec)
                val total = candidates.size
                var processed = 0

                if (total == 0) {
                    updateNotification("Vault is up to date!", 0, 0)
                    health.endIndexingSession()
                    delay(2000)
                    stopSelf()
                    return@launch
                }

                updateNotification("Found $total photos to index...", 0, total)

                val pipeline = IndexingPipeline.getInstance(applicationContext)
                val policy = ThermalAdaptiveBatchPolicy(applicationContext)

                engine.process(
                    candidates = candidates,
                    policy = policy,
                    isActive = { isRunning },
                    indexOne = { _, _, uri ->
                        var bmp: Bitmap? = null
                        try {
                            bmp = BitmapDecoding.loadSoftware(this@IndexingForegroundService, uri)
                            if (bmp != null) {
                                pipeline.indexBitmap(bmp, uri.toString())
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            // ELITE FIX: Guaranteed memory cleanup even if OCR crashes
                            bmp?.recycle()
                            bmp = null
                        }
                        processed++
                    },
                    onBatchCommitted = { _, _ ->
                        // ELITE FIX: Native Android Progress Bar
                        updateNotification("Processing...", processed, total)
                    },
                )

                if (isRunning) {
                    updateNotification("✓ Successfully indexed $processed photos", total, total)
                    // Durable checkpoint: persist HNSW graph + id-mappings together.
                    runCatching { VectorSearchManager.getInstance(applicationContext).saveState() }
                        .onFailure { VaultLog.w("IndexingService", "Vector saveState failed", it) }
                    health.endIndexingSession()
                    delay(3000)
                }
                stopSelf()

            } catch (e: Exception) {
                updateNotification("Indexing failed: ${e.message}", 0, 0)
                delay(3000)
                stopSelf()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vault Indexing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while indexing your photos"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progress: Int, max: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Amar Vault")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Adds the actual progress bar graphic to the notification!
        if (max > 0) {
            builder.setProgress(max, progress, false)
        } else {
            builder.setProgress(0, 0, false) // Hides progress bar
        }

        return builder.build()
    }

    private fun updateNotification(text: String, progress: Int, max: Int) {
        val notification = buildNotification(text, progress, max)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "amar_indexing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_INDEX_ALL = "com.amar.vault.ACTION_INDEX_ALL"
        const val ACTION_STOP = "com.amar.vault.ACTION_STOP"

        fun startIndexing(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_INDEX_ALL
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, IndexingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}