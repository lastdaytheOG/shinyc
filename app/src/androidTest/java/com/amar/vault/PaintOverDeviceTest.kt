package com.amar.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amar.vault.indexing.FoundBlock
import com.amar.vault.indexing.FoundLine
import com.amar.vault.indexing.FoundText
import com.amar.vault.indexing.PaintOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaintOverDeviceTest {

    private val paper = Color.rgb(250, 248, 240)
    private val band = Color.rgb(30, 90, 160)
    private val ink = Color.rgb(20, 20, 30)

    private fun line(text: String, top: Int, confidence: Float = 0.96f, left: Int = 40): FoundLine =
        FoundLine(text, confidence, Rect(left, top, left + text.length * 22, top + 44))

    private fun found(vararg lines: FoundLine): FoundText =
        FoundText(lines.joinToString("\n") { it.text }, lines.map { FoundBlock(it.text, Rect(it.box), listOf(it)) })

    private fun picture(behind: Map<FoundLine, Int>): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(paper)
        val canvas = Canvas(bitmap)
        for ((line, colour) in behind) {
            val box = line.box!!
            canvas.drawRect(Rect(0, box.top - 30, 1080, box.bottom + 30), Paint().apply { color = colour })
            var x = box.left
            while (x + 10 < box.right) {
                canvas.drawRect(Rect(x, box.top + 6, x + 10, box.bottom - 6), Paint().apply { color = ink })
                x += 18
            }
        }
        return bitmap
    }

    private fun inkIn(bitmap: Bitmap, box: Rect): Int {
        var count = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) {
            val p = bitmap.getPixel(x, y)
            if (Color.red(p) < 25 && Color.blue(p) < 35) count++
        }
        return count
    }

    @Test
    fun aSureLineIsCoveredInTheColourAroundItAndAnUnsureOneIsLeft() {
        val sure = line("Monthly statement", 100)
        val onBand = line("Paid within seven days", 700)
        val unsure = line("Balance carried forward", 400, confidence = 0.5f)
        val source = picture(mapOf(sure to paper, onBand to band, unsure to paper))

        val painted = PaintOver.painted(source, found(sure, onBand, unsure))!!

        assertTrue(inkIn(source, sure.box!!) > 0)
        assertEquals(0, inkIn(painted, sure.box!!))
        assertEquals(0, inkIn(painted, onBand.box!!))
        assertEquals(inkIn(source, unsure.box!!), inkIn(painted, unsure.box!!))
        assertEquals(paper, painted.getPixel(sure.box!!.centerX(), sure.box!!.centerY()))
        assertEquals(band, painted.getPixel(onBand.box!!.centerX(), onBand.box!!.centerY()))
        assertEquals(source.getPixel(5, 5), painted.getPixel(5, 5))
    }

    @Test
    fun nothingIsPaintedWhenNoLineIsSure() {
        val unsure = line("Balance carried forward", 400, confidence = 0.5f)

        assertNull(PaintOver.painted(picture(mapOf(unsure to paper)), found(unsure)))
    }
}
