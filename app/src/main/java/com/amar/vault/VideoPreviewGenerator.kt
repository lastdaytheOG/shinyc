package com.amar.vault

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

object VideoPreviewGenerator {
    fun generateVideoFrame(context: Context, uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content" || uri.scheme == "file") {
                retriever.setDataSource(context, uri)
            } else {
                val file = File(uriString)
                if (file.exists()) {
                    retriever.setDataSource(file.absolutePath)
                } else return null
            }
            
            // Extract frame at 1.0 second (1,000,000 microseconds)
            // If that fails, it falls back to retrieve any frame (closest sync)
            retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            android.util.Log.e("VideoPreviewGenerator", "Failed to retrieve frame from $uriString: ${e.message}")
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
