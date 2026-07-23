package com.amar.vault.indexing

import android.graphics.Bitmap

/**
 * Fail-closed blank-page test for rendered PDF pages.
 * A page is skipped only when every pixel is opaque white. Any scan noise or faint ink proceeds
 * to OCR, so a false negative costs time but a false positive cannot lose searchable content.
 */
internal object RenderedPdfPageInspector {
    fun isExactlyBlank(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val rowHeight = minOf(32, bitmap.height)
        val pixels = IntArray(bitmap.width * rowHeight)
        var y = 0
        while (y < bitmap.height) {
            val height = minOf(rowHeight, bitmap.height - y)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, height)
            for (index in 0 until bitmap.width * height) if (pixels[index] != -1) return false
            y += height
        }
        return true
    }
}
