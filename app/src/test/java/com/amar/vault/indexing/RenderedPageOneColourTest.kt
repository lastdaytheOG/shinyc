package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class RenderedPageOneColourTest {

    private fun page(colour: Int) = Bitmap.createBitmap(120, 170, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }

    @Test
    fun aSolidBlackPageIsOneColourAndNotBlank() {
        val black = page(Color.BLACK)
        assertTrue(RenderedPdfPageInspector.isOneColour(black))
        assertFalse(RenderedPdfPageInspector.isExactlyBlank(black))
    }

    @Test
    fun aWhitePageIsBothBlankAndOneColour() {
        val white = page(Color.WHITE)
        assertTrue(RenderedPdfPageInspector.isExactlyBlank(white))
        assertTrue(RenderedPdfPageInspector.isOneColour(white))
    }

    @Test
    fun oneDotOfInkMakesAPageReadable() {
        val black = page(Color.BLACK).apply { setPixel(119, 169, Color.WHITE) }
        val white = page(Color.WHITE).apply { setPixel(0, 169, Color.BLACK) }
        assertFalse(RenderedPdfPageInspector.isOneColour(black))
        assertFalse(RenderedPdfPageInspector.isOneColour(white))
        assertFalse(RenderedPdfPageInspector.isExactlyBlank(white))
    }
}
