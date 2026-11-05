package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream

/**
 * Single owner of image decoding for indexing workers and observers.
 *
 * Provides:
 * 1. ImageDecoder-based software loading for foreground, nightly, and screenshot observers ([loadSoftware]).
 * 2. Bounded stream decoding for bulk scanning and folder sync ([decodeStreamBounded]), limiting the
 *    short side (or total pixel volume) rather than the long side so long scrolling screenshots keep full detail.
 */
object BitmapDecoding {

    /** Maximum short-side resolution allowed before downsampling (3200 px preserves 12 MP camera photos at full 100% detail). */
    const val MAX_SHORT_SIDE: Int = 3200

    /** Maximum total pixels before downsampling (20 MP allows long scrolling screenshots up to ~1080x18000). */
    const val MAX_TOTAL_PIXELS: Long = 20_000_000L

    /**
     * Calculates the power-of-two [BitmapFactory.Options.inSampleSize] required to bring [width] and [height]
     * within [maxShortSide] and [maxTotalPixels].
     *
     * Limits the short side (or total pixels), not the long side, so long scrolling screenshots
     * (e.g. 1080x9000) keep full detail (sample size 1).
     */
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxShortSide: Int = MAX_SHORT_SIDE,
        maxTotalPixels: Long = MAX_TOTAL_PIXELS
    ): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        val shortSide = minOf(width, height)
        while (
            (shortSide / sampleSize) > maxShortSide ||
            ((width.toLong() / sampleSize) * (height / sampleSize)) > maxTotalPixels
        ) {
            sampleSize *= 2
        }
        return maxOf(1, sampleSize)
    }

    /**
     * Decodes an image from a stream provider, bounding dimensions by short side and total pixels.
     * Uses two passes: bounds inspection, then bounded decoding.
     */
    fun decodeStreamBounded(
        openStream: () -> InputStream?,
        maxShortSide: Int = MAX_SHORT_SIDE,
        maxTotalPixels: Long = MAX_TOTAL_PIXELS
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxShortSide, maxTotalPixels)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            openStream()?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * @param mutable maps to `ImageDecoder.isMutableRequired`. When false (default) the decoder is
     *   left at its default, exactly as the immutable callers did (they never set the flag).
     */
    fun loadSoftware(context: Context, uri: Uri, mutable: Boolean = false): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // required for ML Kit / Tesseract
                    if (mutable) decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }
}
