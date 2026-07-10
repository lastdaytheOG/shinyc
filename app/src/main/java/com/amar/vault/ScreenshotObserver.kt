package com.amar.vault

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.PriorityBlockingQueue

data class IndexJob(
    val uri: Uri,
    val timestamp: Long,
    val isRealtime: Boolean
)

class ScreenshotObserver(
    private val context: Context,
    private val scope: CoroutineScope,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private val priorityQueue = PriorityBlockingQueue<IndexJob>(
        100,
        compareByDescending { it.timestamp }
    )

    private val recentlyQueued = mutableSetOf<String>()
    private val recentMutex    = Mutex()

    // ELITE FIX: Protects the ML pipeline from concurrent execution OOMs
    private val processingMutex = Mutex()

    // Dynamic write delay based on Android version
    private val writeDelay: Long = when {
        Build.VERSION.SDK_INT >= 33 -> 150L  // Android 13+ — faster pipeline
        Build.VERSION.SDK_INT >= 31 -> 175L  // Android 12
        else                        -> 200L  // Android 11 and below
    }

    fun register() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this
        )
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        uri ?: return
        val uriStr = uri.toString()
        android.util.Log.d("ObserverTiming", "onChange fired: ${System.currentTimeMillis()}")

        scope.launch(Dispatchers.IO) {
            recentMutex.withLock {
                if (uriStr in recentlyQueued) return@launch
                recentlyQueued.add(uriStr)
            }

            // Auto-clear the deduplication cache after 5 seconds
            scope.launch(Dispatchers.IO) {
                delay(5000)
                recentMutex.withLock { recentlyQueued.remove(uriStr) }
            }

            delay(writeDelay)

            val isScreenshot = isScreenshotUri(context, uri)
            priorityQueue.offer(
                IndexJob(
                    uri        = uri,
                    timestamp  = System.currentTimeMillis(),
                    isRealtime = isScreenshot
                )
            )
            drainQueue()
        }
    }

    private suspend fun drainQueue() {
        // ELITE FIX: If another coroutine is already draining the queue, skip.
        // This guarantees only ONE image is passed through ML Kit/ONNX at a time.
        if (!processingMutex.tryLock()) return

        try {
            android.util.Log.d("ObserverTiming", "Pipeline started: ${System.currentTimeMillis()}")

            while (priorityQueue.isNotEmpty()) {
                val job = priorityQueue.poll() ?: break
                var bmp: android.graphics.Bitmap? = null

                try {
                    bmp = com.amar.vault.indexing.BitmapDecoding.loadSoftware(context, job.uri)
                    if (bmp != null) {
                        // ELITE FIX: Removed redundant pHash calculation.
                        // We now let IndexingPipeline handle hashing entirely!
                        IndexingPipeline.getInstance(context).indexBitmap(
                            bmp,
                            job.uri.toString(),
                            if (job.isRealtime) "screenshot" else "photo"
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    // ELITE FIX: Guaranteed cleanup prevents memory leaks on corrupted images
                    bmp?.recycle()
                    bmp = null
                }
            }
            android.util.Log.d("ObserverTiming", "Pipeline done: ${System.currentTimeMillis()}")
        } finally {
            processingMutex.unlock()
            // If new jobs arrived while we were processing, drain them now
            if (priorityQueue.isNotEmpty()) {
                drainQueue()
            }
        }
    }

    private fun isScreenshotUri(context: Context, uri: Uri): Boolean {
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val cursor = context.contentResolver.query(
                uri, projection, null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val path = it.getString(
                        it.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    )?.lowercase() ?: ""
                    val name = it.getString(
                        it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    )?.lowercase() ?: ""
                    isScreenshotPath(path) || isScreenshotName(name)
                } else false
            } ?: false
        } catch (e: Exception) { false }
    }

    private fun isScreenshotPath(path: String): Boolean =
        SCREENSHOT_PATHS.any { path.contains(it, ignoreCase = true) }

    private fun isScreenshotName(name: String): Boolean =
        SCREENSHOT_NAME_PATTERNS.any { name.contains(it, ignoreCase = true) }

    companion object {
        private val SCREENSHOT_PATHS = listOf(
            "pictures/screenshots",
            "dcim/screenshots",
            "screenshots",
        )
        private val SCREENSHOT_NAME_PATTERNS = listOf(
            "screenshot",
            "screen_shot",
            "screen-shot",
        )
    }
}