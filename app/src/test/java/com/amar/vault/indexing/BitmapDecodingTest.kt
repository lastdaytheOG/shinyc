package com.amar.vault.indexing

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class BitmapDecodingTest {

    @Test
    fun sampleSizePreservesLongScrollingScreenshot() {
        // 1080x9000 scrolling screenshot: short side is 1080 (<= 2160), total pixels 9.72 MP (<= 16 MP)
        assertEquals(1, BitmapDecoding.calculateInSampleSize(1080, 9000))
        // Horizontal orientation
        assertEquals(1, BitmapDecoding.calculateInSampleSize(9000, 1080))
        // Typical modern phone screenshot
        assertEquals(1, BitmapDecoding.calculateInSampleSize(1080, 2400))
    }

    @Test
    fun sampleSizeDownsamplesHighMegapixelPhotos() {
        // 12 MP camera photo (4000x3000): short side 3000 > 2160 -> sampleSize 2 (decodes as 2000x1500)
        assertEquals(2, BitmapDecoding.calculateInSampleSize(4000, 3000))
        assertEquals(2, BitmapDecoding.calculateInSampleSize(3000, 4000))

        // 48 MP camera photo (8000x6000): short side 6000, total 48 MP -> sampleSize 4 (decodes as 2000x1500)
        assertEquals(4, BitmapDecoding.calculateInSampleSize(8000, 6000))
    }

    @Test
    fun decodeBoundedPreservesScrollingScreenshotAtFullResolution() {
        // Create a 1080x9000 bitmap and compress to in-memory PNG
        val original = Bitmap.createBitmap(1080, 9000, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            original.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        original.recycle()

        val decoded = BitmapDecoding.decodeStreamBounded(
            openStream = { ByteArrayInputStream(bytes) }
        )
        assertNotNull(decoded)
        assertEquals(1080, decoded!!.width)
        assertEquals(9000, decoded.height)
        decoded.recycle()
    }

    @Test
    fun decodeBoundedDownsamplesLargeCameraPhoto() {
        // Create a 4000x3000 bitmap and compress to in-memory JPEG
        val original = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            original.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        original.recycle()

        val decoded = BitmapDecoding.decodeStreamBounded(
            openStream = { ByteArrayInputStream(bytes) }
        )
        assertNotNull(decoded)
        assertEquals(2000, decoded!!.width)
        assertEquals(1500, decoded.height)
        decoded.recycle()
    }
}
