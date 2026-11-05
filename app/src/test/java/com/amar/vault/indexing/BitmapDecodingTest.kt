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
    fun sampleSizePreservesLongScrollingScreenshotAnd12MpPhotos() {
        // 1080x9000 scrolling screenshot: short side is 1080 (<= 3200), total pixels 9.72 MP (<= 20 MP)
        assertEquals(1, BitmapDecoding.calculateInSampleSize(1080, 9000))
        // Horizontal orientation
        assertEquals(1, BitmapDecoding.calculateInSampleSize(9000, 1080))
        // Typical modern phone screenshot
        assertEquals(1, BitmapDecoding.calculateInSampleSize(1080, 2400))
        // 12 MP camera photo (4000x3000): short side 3000 <= 3200, total 12 MP <= 20 MP -> kept at full 1:1 detail
        assertEquals(1, BitmapDecoding.calculateInSampleSize(4000, 3000))
        assertEquals(1, BitmapDecoding.calculateInSampleSize(3000, 4000))
    }

    @Test
    fun sampleSizeDownsamplesOnly48MpPlusPhotos() {
        // 48 MP camera photo (8000x6000): short side 6000 > 3200 -> sampleSize 2 (decodes as 4000x3000)
        assertEquals(2, BitmapDecoding.calculateInSampleSize(8000, 6000))
        // 108 MP camera photo (12000x9000): short side 9000 > 3200 -> sampleSize 4 (decodes as 3000x2250)
        assertEquals(4, BitmapDecoding.calculateInSampleSize(12000, 9000))
    }

    @Test
    fun decodeBoundedPreservesScrollingScreenshotAtFullResolution() {
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
    fun decodeBoundedPreserves12MpCameraPhotoAtFullResolution() {
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
        assertEquals(4000, decoded!!.width)
        assertEquals(3000, decoded.height)
        decoded.recycle()
    }
}
