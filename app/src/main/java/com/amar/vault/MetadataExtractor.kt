package com.amar.vault

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

object MetadataExtractor {

    data class ExtractedMetadata(
        val width: Int? = null,
        val height: Int? = null,
        val duration: Long? = null,
        val fileSize: Long? = null,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    fun extract(context: Context, file: File, mimeType: String): ExtractedMetadata {
        var width: Int? = null
        var height: Int? = null
        var duration: Long? = null
        val fileSize: Long = file.length()
        var latitude: Double? = null
        var longitude: Double? = null

        try {
            if (mimeType.startsWith("image/")) {
                val exif = ExifInterface(file.absolutePath)
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                
                val latLong = exif.latLong
                if (latLong != null && latLong.size == 2) {
                    latitude = latLong[0]
                    longitude = latLong[1]
                }
            } else if (mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (mimeType.startsWith("video/")) {
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    
                    // Handle rotation
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rotation == 90 || rotation == 270) {
                        val temp = width
                        width = height
                        height = temp
                    }
                }
                retriever.release()
            }
        } catch (e: Exception) {
            android.util.Log.e("MetadataExtractor", "Failed to extract metadata for ${file.absolutePath}", e)
        }

        return ExtractedMetadata(
            width = width,
            height = height,
            duration = duration,
            fileSize = fileSize,
            latitude = latitude,
            longitude = longitude
        )
    }
}
