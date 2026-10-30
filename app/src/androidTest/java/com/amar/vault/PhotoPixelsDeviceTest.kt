package com.amar.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amar.vault.photos.PhotoPixels
import com.amar.vault.photos.PhotoResize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The photo model's pixels from native code are the same bits as from Kotlin, and Kotlin agrees
 * with PIL (PhotoPixelsTest): a vector made from either is the same vector. Pictures of several
 * shapes and sizes, shrunk, enlarged and left at size; bitmaps the native path must not take go the
 * Kotlin way and so match by construction, which is checked too.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPixelsDeviceTest {

    private fun assertSameBits(what: String, kotlin: FloatArray, other: FloatArray) {
        assertEquals(what, kotlin.size, other.size)
        for (i in kotlin.indices) {
            if (kotlin[i].toRawBits() != other[i].toRawBits()) {
                throw AssertionError("$what: value $i is ${kotlin[i]} from Kotlin, ${other[i]} from native")
            }
        }
    }

    private fun picture(w: Int, h: Int, seed: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val r = Random(seed)
        val b = Bitmap.createBitmap(w, h, config)
        val c = Canvas(b)
        c.drawColor(Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256)))
        val p = Paint().apply { isAntiAlias = true }
        repeat(60) {
            p.color = Color.argb(r.nextInt(128, 256), r.nextInt(256), r.nextInt(256), r.nextInt(256))
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, r.nextFloat() * maxOf(w, h) / 6f, p)
            p.textSize = 8f + r.nextFloat() * 60f
            c.drawText("sample ${r.nextInt()}", r.nextFloat() * w, r.nextFloat() * h, p)
        }
        return b
    }

    /** What the bulk scan reads: a decoded JPEG, opaque and in sRGB. */
    private fun decoded(b: Bitmap): Bitmap {
        val bytes = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes))!!
    }

    @Test
    fun nativeGivesKotlinsBits() {
        var seed = 1
        val shapes = listOf(
            4080 to 3060, 3060 to 4080, 1600 to 1200, 1080 to 2400, 640 to 480, 512 to 384, 257 to 255,
            256 to 256, 255 to 257, 300 to 40, 40 to 300, 128 to 176, 37 to 23, 10 to 7, 1 to 1, 2 to 3,
        )
        for ((w, h) in shapes) for (size in listOf(256, 224, 16)) {
            val made = picture(w, h, seed++)
            val jpeg = decoded(made)
            made.recycle()
            assertTrue("a decoded JPEG is opaque", !jpeg.hasAlpha())
            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), jpeg.colorSpace)
            val native = PhotoResize.of(jpeg, PhotoPixels.Taps(w, size), PhotoPixels.Taps(h, size), size)
            assertTrue("a decoded JPEG takes the native way", native != null)
            assertSameBits("${w}x$h to $size", PhotoPixels.ofInKotlin(jpeg, size), native!!)
            assertSameBits("${w}x$h to $size through of()", PhotoPixels.ofInKotlin(jpeg, size), PhotoPixels.of(jpeg, size))
            jpeg.recycle()
        }
    }

    @Test
    fun aBitmapOtherThanOpaqueSrgbStillComesOutRight() {
        val size = 64
        val translucent = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.argb(120, 200, 40, 90))
        }
        val p3 = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3)).also {
            Canvas(it).drawColor(Color.rgb(200, 40, 90))
        }
        val rgb565 = picture(300, 200, 99, Bitmap.Config.RGB_565)
        for ((name, b) in listOf("translucent" to translucent, "display p3" to p3, "565" to rgb565)) {
            assertSameBits(name, PhotoPixels.ofInKotlin(b, size), PhotoPixels.of(b, size))
            b.recycle()
        }
    }
}
