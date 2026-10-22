package com.amar.vault.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The photo model reads pictures resized the way it was trained: PIL's antialiased bicubic
 * resize. [PhotoPixels.resized] gives PIL's own bytes (`photos/pil_bicubic_cases.txt`, written by
 * `tools/visual-search/make_resize_cases.py`) to within one step of 255, shrinking by 2, by an
 * uneven factor, and enlarging, on pictures of smooth shapes and noise.
 */
class PhotoPixelsTest {

    @Test
    fun resizesAsPilDoes() {
        val lines = javaClass.classLoader!!.getResourceAsStream("photos/pil_bicubic_cases.txt")!!
            .bufferedReader(Charsets.US_ASCII).readLines().filter { it.isNotBlank() }
        assertEquals(0, lines.size % 3)
        for (c in lines.indices step 3) {
            val (w, h, size) = lines[c].split(' ').map { it.toInt() }
            val input = lines[c + 1].split(' ').map { it.toInt() }
            val expected = lines[c + 2].split(' ').map { it.toInt() }
            val argb = IntArray(w * h) { i ->
                (0xFF shl 24) or (input[3 * i] shl 16) or (input[3 * i + 1] shl 8) or input[3 * i + 2]
            }
            val planar = PhotoPixels.resized(argb, w, h, size)
            val plane = size * size
            var worst = 0
            for (i in 0 until plane) for (ch in 0 until 3) {
                val got = ((planar[ch * plane + i] + 1f) * 127.5f).roundToInt()
                worst = maxOf(worst, abs(got - expected[3 * i + ch]))
            }
            assertTrue("${w}x$h to $size: off by up to $worst", worst <= 1)
        }
    }
}
