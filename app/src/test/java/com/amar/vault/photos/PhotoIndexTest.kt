package com.amar.vault.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhotoIndexTest {

    private val dim = 768
    private val rnd = Random(7)

    private fun unit(): FloatArray = PhotoVectors.normalized(FloatArray(dim) { rnd.nextFloat() * 2 - 1 })

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    @Test
    fun aStoredVectorKeepsItsDirection() {
        repeat(50) {
            val v = unit()
            val (bytes, scale) = PhotoVectors.quantize(v)
            val back = PhotoVectors.normalized(PhotoVectors.dequantize(bytes, scale))
            assertTrue(dot(v, back) > 0.9999f)
        }
    }

    @Test
    fun theScanRanksLikeTheFloatVectorsDo() {
        val vectors = (0 until 2000).associate { "p$it" to unit() }
        val index = PhotoIndex(dim)
        for ((id, v) in vectors) {
            val (bytes, scale) = PhotoVectors.quantize(v)
            index.put(id, bytes, scale)
        }
        repeat(20) {
            val q = unit()
            val exact = vectors.values.map { dot(q, it) }.sortedDescending().take(10)
            val found = index.top(q, 10)
            assertEquals(found.sortedByDescending { it.score }, found)
            // Byte storage moves a score by about 1e-3 at most. Two pictures closer than that may
            // swap places, so the k-th picture found has the k-th best true score, give or take that.
            found.forEachIndexed { k, m ->
                val trueScore = dot(q, vectors.getValue(m.itemId))
                assertEquals(trueScore, m.score, 2e-3f)
                assertEquals(exact[k], trueScore, 4e-3f)
            }
        }
    }

    @Test
    fun aPictureCanBeReplacedAndRemoved() {
        val index = PhotoIndex(dim)
        val a = unit(); val b = unit(); val c = unit()
        fun put(id: String, v: FloatArray) = PhotoVectors.quantize(v).let { (bytes, s) -> index.put(id, bytes, s) }
        put("a", a); put("b", b); put("c", c)
        assertEquals(3, index.size)
        assertEquals("a", index.top(a, 1).single().itemId)

        put("a", c) // the picture under "a" changed
        assertEquals(3, index.size)
        assertEquals(setOf("a", "c"), index.top(c, 2).map { it.itemId }.toSet())

        assertTrue(index.remove("a"))
        assertFalse(index.remove("a"))
        assertEquals(2, index.size)
        assertFalse(index.contains("a"))
        assertEquals("b", index.top(b, 1).single().itemId)
        assertEquals("c", index.top(c, 1).single().itemId)
    }

    @Test
    fun onlyNarrowsWhatMayBeListed() {
        val index = PhotoIndex(dim)
        val vs = (0 until 50).map { unit() }
        vs.forEachIndexed { i, v -> PhotoVectors.quantize(v).let { (bytes, s) -> index.put("p$i", bytes, s) } }
        val q = vs[3]
        assertEquals("p3", index.top(q, 1).single().itemId)
        val found = index.top(q, 5) { it != "p3" }
        assertFalse(found.any { it.itemId == "p3" })
        assertEquals(5, found.size)
    }

    @Test
    fun loadKeepsOnlyVectorsOfTheIndexDimension() {
        val index = PhotoIndex(dim)
        val good = PhotoVectors.quantize(unit())
        index.load(listOf(
            PhotoVector("ok", "m", 0, good.first, good.second, 0),
            PhotoVector("wrong", "m", 0, ByteArray(3), 1f, 0),
        ))
        assertEquals(1, index.size)
        assertTrue(index.contains("ok"))
    }

    @Test
    fun anEmptyIndexFindsNothing() {
        assertEquals(emptyList<PhotoMatch>(), PhotoIndex(dim).top(unit(), 10))
    }
}
