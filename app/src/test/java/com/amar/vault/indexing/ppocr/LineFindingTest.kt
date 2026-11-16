package com.amar.vault.indexing.ppocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class LineFindingTest {

    private val settings = FinderSettings(longSide = 640)
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    @Test
    fun thePictureIsShrunkForTheFinderAsTheReferenceShrinksIt() {
        val screen = PpOcrCases.picture("screen.bin.gz")
        val reference = PpOcrCases.picture("resized.bin.gz")

        val input = LineFinding.input(screen.argb, screen.width, screen.height, settings.longSide)

        assertEquals(reference.width, input.width)
        assertEquals(reference.height, input.height)
        val plane = input.width * input.height
        var worst = 0
        var off = 0
        for (i in 0 until plane) {
            for (channel in 0 until 3) {
                val mine = Math.round((input.pixels[channel * plane + i] * std[channel] + mean[channel]) * 255f)
                val theirs = (reference.argb[i] shr (8 * channel)) and 0xFF
                val gap = abs(mine - theirs)
                worst = max(worst, gap)
                if (gap > 0) off++
            }
        }
        assertTrue("worst gap $worst", worst <= 1)
        assertTrue("values off by one: $off of ${3 * plane}", off < 3 * plane / 100)
    }

    @Test
    fun aPictureNoLargerThanTheLimitKeepsItsSizeRoundedToThirtyTwo() {
        val input = LineFinding.input(IntArray(700 * 500), 700, 500, 1280)

        assertEquals(704, input.width)
        assertEquals(512, input.height)
    }

    @Test
    fun theLinesFoundAreTheOnesTheReferenceFinds() {
        val map = PpOcrCases.probMap()
        val reference = PpOcrCases.places()

        val found = LineFinding.places(map.prob, map.width, map.height, 720, 960, settings)

        assertEquals(reference.size, found.size)
        val taken = HashSet<Int>()
        for (theirs in reference) {
            val nearest = found.indices.minByOrNull { gap(found[it].corners, theirs.corners) }!!
            val mine = found[nearest]
            assertTrue(
                "${theirs.corners.toList()} was found at ${mine.corners.toList()}",
                gap(mine.corners, theirs.corners) <= 2f,
            )
            assertEquals(theirs.score, mine.score, 0.02f)
            assertTrue("one place answers two reference places", taken.add(nearest))
        }
    }

    @Test
    fun aBlankMapHasNoLines() {
        assertTrue(LineFinding.places(FloatArray(64 * 64), 64, 64, 640, 640, settings).isEmpty())
    }

    @Test
    fun aSpeckIsNotALine() {
        val prob = FloatArray(64 * 64)
        prob[20 * 64 + 20] = 0.9f
        prob[20 * 64 + 21] = 0.9f

        assertTrue(LineFinding.places(prob, 64, 64, 640, 640, settings).isEmpty())
    }

    @Test
    fun aBarOnTheMapIsOneLineGrownOnEverySideAndScaledToThePicture() {
        val prob = FloatArray(100 * 100)
        for (y in 40..49) for (x in 20..79) prob[y * 100 + x] = 0.9f

        val found = LineFinding.places(prob, 100, 100, 200, 200, settings)

        assertEquals(1, found.size)
        val c = found.single().corners
        val by = 59f * 9f * 1.4f / (2 * (59f + 9f))
        assertEquals((20 - by) * 2, c[0], 2f)
        assertEquals((40 - by) * 2, c[1], 2f)
        assertEquals((79 + by) * 2, c[4], 2f)
        assertEquals((49 + by) * 2, c[5], 2f)
        assertEquals(0.9f, found.single().score, 0.001f)
    }

    private fun gap(a: FloatArray, b: FloatArray): Float {
        var worst = 0f
        for (i in 0 until 8) worst = max(worst, abs(a[i] - b[i]))
        return worst
    }
}
