package com.amar.vault.photos

import com.amar.vault.VaultConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoGateTest {

    private fun unit(vararg v: Float): FloatArray {
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    // Axis 0 is "any photo"; axis 1 a cat; axis 2 a dog.
    private val generic = listOf(unit(1f, 0f, 0f))
    private val vectors = mapOf(
        "cat" to unit(0.3f, 1f, 0f),
        "dog" to unit(0.3f, 0f, 1f),
        "blurry" to unit(1f, 0.1f, 0.1f),
    )

    private fun indexOf(photos: Map<String, FloatArray>, words: List<FloatArray> = generic): PhotoIndex =
        PhotoIndex(photos.values.first().size, words).also { index ->
            for ((id, v) in photos) PhotoVectors.quantize(v).let { (bytes, scale) -> index.put(id, bytes, scale) }
        }

    private fun search(query: FloatArray, margin: Float = 0.05f): List<String> =
        PhotoGate.matches(indexOf(vectors), query, margin).map { it.itemId }

    private fun alike(score: Float, n: Int, common: Float = 0f): FloatArray {
        val rest = kotlin.math.sqrt(1f - score * score - common * common)
        val spread = n * 0.0007f
        return floatArrayOf(common, score, rest * kotlin.math.cos(spread), rest * kotlin.math.sin(spread))
    }

    private val anyPhoto = listOf(floatArrayOf(1f, 0f, 0f, 0f))
    private val subject = floatArrayOf(0f, 1f, 0f, 0f)

    private fun listed(photos: Map<String, FloatArray>, only: ((String) -> Boolean)? = null) =
        PhotoGate.matches(indexOf(photos, anyPhoto), subject, 0.05f, only = only).map { it.itemId }

    @Test fun thePhotoTheQueryDescribesIsKept() = assertEquals(listOf("cat"), search(unit(0f, 1f, 0f)))

    @Test fun theClosestPhotoIsLeftOutWhenAnyPhotoWordsFitItAsWell() {
        // The blurry picture is the most alike by plain similarity, and "a photo" fits it just as
        // well: not a match. The cat, which the query leans to, is.
        assertEquals(listOf("cat"), search(unit(0.9f, 0.3f, 0f)))
    }

    @Test fun aQueryNoPhotoShowsListsNone() = assertEquals(emptyList<String>(), search(unit(1f, 0f, 0f)))

    @Test fun fixedBarDoesNotDropAValidMatchBecauseAnotherScoresHigher() {
        assertEquals(listOf("cat", "dog"), search(unit(0f, 1f, 0.6f)))
    }

    @Test fun eachWayOfWritingHasItsMargin() {
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN, PhotoGate.marginFor("dog in the snow"))
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN_HINDI, PhotoGate.marginFor("बर्फ़ में कुत्ता"))
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN_ROMANISED, PhotoGate.marginFor("barf mein kutta"))
        // An English word that only starts like a Hindi one is English.
        assertEquals(com.amar.vault.VaultConfig.Retrieval.PHOTO_MATCH_MARGIN, PhotoGate.marginFor("kitten on a sofa"))
    }

    @Test fun withoutGenericWordsFixedScoreBarStillApplies() {
        val found = PhotoGate.matches(indexOf(vectors, emptyList()), unit(1f, 0f, 0f)).map { it.itemId }
        assertEquals(listOf("blurry"), found)
    }

    @Test fun aPhotoListedInASmallVaultIsStillListedInALargeOne() {
        val early = (0 until 24).associate { "early$it" to alike(0.40f + it * 0.002f, it) }
        val small = listed(early)
        assertEquals(24, small.size)
        val grown = early + (0 until 400).associate { "later$it" to alike(0.70f + it * 0.0005f, it + 30) }
        assertEquals(emptySet<String>(), small.toSet() - listed(grown).toSet())
    }

    @Test fun photosLikeEveryQueryDoNotPushARealMatchOut() {
        val real = mapOf("real" to alike(0.45f, 0))
        val likeEverything = (0 until 100).associate { "any$it" to alike(0.50f, it + 1, common = 0.60f) }
        assertEquals(listOf("real"), listed(real + likeEverything))
    }

    @Test fun itemsBelowTheFixedBarAreNotListedAsMatches() {
        val faint = (0 until 100).associate { "faint$it" to alike(0.20f, it) }
        assertEquals(emptyList<String>(), listed(faint))
    }

    @Test fun aPeriodNarrowsTheSureMatchesToo() {
        val photos = (0 until 80).associate { "p$it" to alike(0.60f, it) }
        assertEquals(setOf("p3", "p70"), listed(photos, only = { it == "p3" || it == "p70" }).toSet())
    }

    @Test fun aRemovedPhotoTakesItsMarginWithIt() {
        val index = indexOf(mapOf("gone" to alike(0.5f, 0, common = 0.6f), "kept" to alike(0.6f, 1)), anyPhoto)
        index.remove("gone")
        assertEquals(0.6f, index.top(subject, 1).single().margin, 0.01f)
    }

    @Test fun photoBarIsFixed() {
        assertEquals(0.49f, VaultConfig.Retrieval.PHOTO_SURE_STRENGTH, 1e-6f)
    }
}
