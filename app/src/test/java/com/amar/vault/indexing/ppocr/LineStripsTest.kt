package com.amar.vault.indexing.ppocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LineStripsTest {

    private val screen = PpOcrCases.picture("screen.bin.gz")
    private val places = PpOcrCases.places()
    private val cases = PpOcrCases.strips()

    @Test
    fun linesAreBatchedByShapeAsTheReferenceBatchesThem() {
        val strips = places.map { Strip(it) }
        val order = strips.indices.sortedBy { strips[it].shape }

        for (group in order.chunked(6)) {
            val batchWidth = LineStrips.batchWidth(group.map { strips[it] })
            for (index in group) {
                val case = cases.single { it.place == index }
                assertEquals("batch width of line $index", case.batchWidth, batchWidth)
                assertEquals("width of line $index", case.wide, LineStrips.width(strips[index], batchWidth))
            }
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
