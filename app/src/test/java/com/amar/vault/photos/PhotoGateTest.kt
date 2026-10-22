package com.amar.vault.photos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A photo is a match when the query describes it clearly better than words for any photo do, and
 * when it is not far behind the best match: a search for something no photo shows lists none.
 */
class PhotoGateTest {

    private fun unit(vararg v: Float): FloatArray {
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    // Axis 0 is "any photo"; axis 1 a hand; axis 2 a dog.
    private val generic = listOf(unit(1f, 0f, 0f))
    private val vectors = mapOf(
        "hand" to unit(0.3f, 1f, 0f),
        "dog" to unit(0.3f, 0f, 1f),
        "blurry" to unit(1f, 0.1f, 0.1f),
    )

    private fun search(query: FloatArray, margin: Float = 0.05f, gap: Float = 0.08f): List<String> {
        val candidates = vectors.map { (id, v) -> PhotoMatch(id, v.indices.sumOf { (v[it] * query[it]).toDouble() }.toFloat()) }
            .sortedByDescending { it.score }
        return PhotoGate.keep(candidates, generic, vectors::get, margin, gap).map { it.itemId }
    }

    @Test fun thePhotoTheQueryDescribesIsKept() = assertEquals(listOf("hand"), search(unit(0f, 1f, 0f)))

    @Test fun theClosestPhotoIsLeftOutWhenAnyPhotoWordsFitItAsWell() {
        // The blurry picture is the most alike by plain similarity, and "a photo" fits it just as
        // well: not a match. The hand, which the query leans to, is.
        assertEquals(listOf("hand"), search(unit(0.9f, 0.1f, 0f)))
    }

    @Test fun aQueryNoPhotoShowsListsNone() = assertEquals(emptyList<String>(), search(unit(1f, 0f, 0f)))

    @Test fun onlyMatchesCloseToTheBestAreListed() {
        // Both pass the margin, but the dog is far behind the hand: left out unless the gap allows it.
        assertEquals(listOf("hand"), search(unit(0f, 1f, 0.6f), gap = 0.05f))
        assertEquals(listOf("hand", "dog"), search(unit(0f, 1f, 0.6f), gap = 1f))
    }

    @Test fun eachWayOfWritingHasItsMargin() {
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN, PhotoGate.marginFor("dog in the snow"))
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN_HINDI, PhotoGate.marginFor("बर्फ़ में कुत्ता"))
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN_ROMANISED, PhotoGate.marginFor("barf mein kutta"))
        // An English word that only starts like a Hindi one is English.
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN, PhotoGate.marginFor("kitten on a sofa"))
    }

    @Test fun withoutGenericWordsNothingIsTakenOut() {
        val candidates = listOf(PhotoMatch("a", 0.2f), PhotoMatch("b", 0.1f))
        assertEquals(candidates, PhotoGate.keep(candidates, emptyList(), { null }))
    }
}
