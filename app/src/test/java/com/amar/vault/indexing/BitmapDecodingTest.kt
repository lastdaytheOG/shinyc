package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        val original = Bitmap.createBitmap(108, 900, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            original.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        original.recycle()

        val decoded = BitmapDecoding.decodeStreamBounded(maxShortSide = 320) {
            ByteArrayInputStream(bytes)
        }
        assertNotNull(decoded)
        assertEquals(108, decoded!!.width)
        assertEquals(900, decoded.height)
        decoded.recycle()
    }

    @Test
    fun decodeBoundedDownsamplesWhenShortSideExceedsCap() {
        val original = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { out ->
            original.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        original.recycle()

        val decoded = BitmapDecoding.decodeStreamBounded(maxShortSide = 200) {
            ByteArrayInputStream(bytes)
        }
        assertNotNull(decoded)
        assertEquals(200, decoded!!.width)
        assertEquals(150, decoded.height)
        decoded.recycle()
    }

    @Test
    fun loadSoftwareMatchesOpenStoredUnderBoundedCap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        val file = java.io.File(context.cacheDir, "test_load_software_match.jpg")
        file.outputStream().use { out ->
            original.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        original.recycle()

        val uri = Uri.fromFile(file)
        val fromLoadSoftware = BitmapDecoding.loadSoftware(context, uri, maxShortSide = 300)
        val fromOpenStored = BitmapDecoding.decodeStreamBounded(maxShortSide = 300) {
            file.inputStream()
        }

        assertNotNull(fromLoadSoftware)
        assertNotNull(fromOpenStored)
        assertEquals(fromOpenStored!!.width, fromLoadSoftware!!.width)
        assertEquals(fromOpenStored.height, fromLoadSoftware.height)
        assertTrue(SamePicture.of(fromLoadSoftware, fromOpenStored))

        fromLoadSoftware.recycle()
        fromOpenStored.recycle()
        file.delete()
    }
}
