package com.amar.vault

import android.content.Context
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches MediaStore for new images in the user's selected folders.
 * When a new photo is added (from camera, WhatsApp, download, etc.),
 * it auto-indexes it silently in the background.
 *
 * This extends your existing ScreenshotObserver to cover ALL selected folders,
 * not just screenshots.
 */
import kotlinx.coroutines.channels.Channel

class FolderSyncObserver(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var observer: ContentObserver? = null
    private var lastProcessedTime = System.currentTimeMillis() / 1000
    private val changeChannel = Channel<Uri>(Channel.UNLIMITED)

    fun register() {
        // Single worker coroutine sequentially consumer
        scope.launch(Dispatchers.Default) {
            for (uri in changeChannel) {
                try {
                    processNewImage(uri)
                } catch (e: Exception) {
                    Log.w("FolderSync", "Failed to process $uri: ${e.message}")
                }
            }
        }

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (uri == null) return
                changeChannel.trySend(uri)
            }
        }

        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer!!,
        )
        Log.d("FolderSync", "Registered — watching for new photos in selected folders")
    }

    fun unregister() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        observer = null
        changeChannel.close()
        Log.d("FolderSync", "Unregistered")
    }

    private suspend fun processNewImage(uri: Uri) {
        val prefs = ScanPreferences.prefsFlow(context).first()
        val folders = prefs.foldersToScan()

        // Query the image's path to check if it's in a selected folder
        val projection = arrayOf(
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media._ID,
        )

        context.contentResolver.query(
            uri, projection, null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return

            val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val dateCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

            val path = if (pathCol >= 0) cursor.getString(pathCol) ?: "" else ""
            val dateAdded = if (dateCol >= 0) cursor.getLong(dateCol) else 0

            // Skip if already processed (dedup by timestamp)
            if (dateAdded <= lastProcessedTime) return

            // Check if this photo is in a selected folder
            val inSelectedFolder = folders.isEmpty() || // scan all
                    folders.any { folder -> folder.isEmpty() || path.contains(folder, ignoreCase = true) }

            if (!inSelectedFolder) return

            // Index it
            Log.d("FolderSync", "New photo detected in $path — indexing")
            lastProcessedTime = dateAdded

            val stream = context.contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(stream)
            stream.close()

            if (bitmap != null) {
                val itemType = when {
                    path.contains("Screenshot", ignoreCase = true) -> "screenshot"
                    path.contains("Camera", ignoreCase = true) -> "camera"
                    path.contains("WhatsApp", ignoreCase = true) -> "whatsapp"
                    else -> "photo"
                }
                IndexingPipeline.getInstance(context).indexBitmap(bitmap, uri.toString(), itemType)
                bitmap.recycle()
                Log.d("FolderSync", "Indexed new $itemType from $path")
            }
        }
    }
}