package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * Single owner of the ImageDecoder-based bitmap loading shared by the foreground service, the
 * nightly worker, and the screenshot observer.
 *
 * Behaviour is a verbatim consolidation of the three prior identical `loadBitmapSafely` / `loadBitmap`
 * copies: `ImageDecoder` with a software allocator on P+, a `MediaStore.Images.Media.getBitmap`
 * fallback below P, and `null` on any failure. The one validated difference is preserved via
 * [mutable]: the nightly path requires a mutable bitmap, the foreground and screenshot paths do not.
 *
 * The bulk path deliberately uses `BitmapFactory.decodeStream` (a distinct no-EXIF-rotation decode)
 * and is intentionally NOT routed through here.
 */
object BitmapDecoding {

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
