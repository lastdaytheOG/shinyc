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
    fun generateFirstPagePreview(context: Context, uriString: String): Bitmap? {
        if (uriString.isBlank()) return null
        return try {
            val uri = Uri.parse(uriString)
            val pfd = when (uri.scheme) {
                "content", "file" -> {
                    context.contentResolver.openFileDescriptor(uri, "r")
                }
                else -> {
                    val file = File(uriString)
                    if (file.exists()) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } else null
                }
            } ?: return null

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
