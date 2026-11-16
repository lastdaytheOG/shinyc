package com.amar.vault.indexing.ppocr

import com.amar.vault.indexing.LinePlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LineStripsTest {

    private val screen = PpOcrCases.picture("screen.bin.gz")
    private val places = PpOcrCases.places()
    private val cases = PpOcrCases.strips()

    @Test
    fun eachLineGetsTheReferenceWidthAndFillsAsMuchOfItAsTheReference() {
        for ((index, place) in places.withIndex()) {
            val strip = Strip(place)
            val case = cases.single { it.place == index }
            assertEquals("width of line $index", case.batchWidth, LineStrips.widthFor(strip, LineStrips.WIDTHS))
            assertEquals("filled part of line $index", case.wide, LineStrips.width(strip, case.batchWidth))
        }
        assertTrue(cases.map { it.batchWidth }.toSet().containsAll(LineStrips.WIDTHS))
        assertTrue(cases.any { it.batchWidth > LineStrips.WIDTHS.last() && it.wide == it.batchWidth })
    }

    @Test
    fun aLineGetsTheSmallestWidthThatHoldsItAndALongerOneItsOwn() {
        fun line(length: Float, tall: Float = 40f) =
            Strip(LinePlace(floatArrayOf(0f, 0f, length, 0f, length, tall, 0f, tall), 0.9f))

        assertEquals(320, LineStrips.widthFor(line(200f), LineStrips.WIDTHS))
        assertEquals(320, LineStrips.widthFor(line(266f), LineStrips.WIDTHS))
        assertEquals(640, LineStrips.widthFor(line(267f), LineStrips.WIDTHS))
        assertEquals(960, LineStrips.widthFor(line(800f), LineStrips.WIDTHS))
        assertEquals(1200, LineStrips.widthFor(line(1000f), LineStrips.WIDTHS))
        assertEquals(960, LineStrips.widthFor(line(800f), listOf(320, 640)))
    }

    @Test
    fun theInterleavedStripHoldsTheSamePixelsAsTheOneInPlanes() {
        val case = cases.first { it.wide < it.batchWidth }
        val planes = filled(case)
        val interleaved = FloatArray(planes.size)
        LineStrips.fill(
            screen.argb, screen.width, screen.height, Strip(places[case.place]), interleaved, 0, case.batchWidth,
            interleaved = true,
        )
        val plane = LineStrips.HEIGHT * case.batchWidth

        for (channel in 0 until 3) for (at in 0 until plane) {
            assertEquals(planes[channel * plane + at], interleaved[3 * at + channel], 0f)
        }
    }

    @Test
    fun aStraightLineIsCutAndShrunkToTheReferenceStrip() {
        val straight = cases.filter { straight(it.place) }
        assertTrue(straight.size >= 8)

        for (case in straight) {
            val gaps = gaps(case)
            assertTrue("line ${case.place}: mean gap ${gaps.average()}", gaps.average() < 0.2)
            assertTrue("line ${case.place}: worst gap ${gaps.max()}", gaps.max() <= 2)
        }
    }

    @Test
    fun aSlantedOrUprightLineIsCloseToTheReferenceStrip() {
        val slanted = cases.filterNot { straight(it.place) }
        assertTrue(slanted.size >= 2)
        assertTrue(slanted.any { Strip(places[it.place]).turned })

        for (case in slanted) {
            val gaps = gaps(case)
            assertTrue("line ${case.place}: mean gap ${gaps.average()}", gaps.average() < 4.0)
        }
    }

    @Test
    fun whatIsLeftOfTheBatchWidthStaysEmpty() {
        val case = cases.first { it.wide < it.batchWidth }
        val pixels = filled(case)
        val plane = LineStrips.HEIGHT * case.batchWidth

        for (channel in 0 until 3) for (row in 0 until LineStrips.HEIGHT) for (x in case.wide until case.batchWidth) {
            assertEquals(0f, pixels[channel * plane + row * case.batchWidth + x], 0f)
        }
    }

    private fun straight(place: Int): Boolean {
        val c = places[place].corners
        return c[1] == c[3] && c[5] == c[7] && c[0] == c[6] && c[2] == c[4]
    }

    private fun filled(case: PpOcrCases.StripCase): FloatArray {
        val pixels = FloatArray(3 * LineStrips.HEIGHT * case.batchWidth)
        LineStrips.fill(screen.argb, screen.width, screen.height, Strip(places[case.place]), pixels, 0, case.batchWidth)
        return pixels
    }

    private fun gaps(case: PpOcrCases.StripCase): List<Int> {
        val pixels = filled(case)
        val plane = LineStrips.HEIGHT * case.batchWidth
        val gaps = ArrayList<Int>(case.bgr.size)
        for (row in 0 until LineStrips.HEIGHT) for (x in 0 until case.wide) for (channel in 0 until 3) {
            val mine = Math.round((pixels[channel * plane + row * case.batchWidth + x] + 1f) * 127.5f)
            val theirs = case.bgr[(row * case.wide + x) * 3 + channel].toInt() and 0xFF
            gaps += abs(mine - theirs)
        }
        return gaps
    }
}
