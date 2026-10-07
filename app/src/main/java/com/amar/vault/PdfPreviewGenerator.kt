package com.amar.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

object PdfPreviewGenerator {
    /**
     * Opens a stored PDF for reading. A shared PDF's `uri` is the bare path of the app's own
     * copy, which ContentResolver cannot open; a picked one is a content uri.
     */
    fun open(context: Context, uriString: String): ParcelFileDescriptor? {
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "content", "file" -> {
                context.contentResolver.openFileDescriptor(uri, "r")
            }
            else -> {
                val file = File(uriString)
                if (file.exists()) {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } else null
            }
        }
    }

    fun generateFirstPagePreview(context: Context, uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return try {
            val pfd = open(context, uriString) ?: return null

            pfd.use {
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    
                    // Render page at a high-quality preview width (e.g., 360px width)
                    val targetWidth = 360
                    val aspectRatio = page.height.toFloat() / page.width.toFloat()
                    val targetHeight = (targetWidth * aspectRatio).toInt().coerceIn(100, 800)
                    
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE) // default background for transparent PDFs
                    
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    renderer.close()
                    bitmap
                } else {
                    renderer.close()
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PdfPreviewGenerator", "Error rendering PDF page 1 for $uriString: ${e.message}")
            null
        }
    }
}
