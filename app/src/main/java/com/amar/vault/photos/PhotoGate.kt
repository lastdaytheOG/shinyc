package com.amar.vault.photos

import com.amar.vault.VaultConfig

/**
 * Which of the photos most like a query are really a match, so that a search for something no photo
 * shows lists no photos instead of the closest 24 of whatever is there.
 *
 * A photo's similarity to a query is not a probability: one photo is close to every query and
 * another to none, so no single floor tells a match from the closest of the rest. What does is how
 * much better the query describes the photo than words that describe any photo ("a photo", "an
 * image"): a photo of a cat beats them clearly for "cat"; for a person's name every photo is
 * described as well by "a photo" as by the query. A photo is kept when that margin is at least
 * [VaultConfig.Retrieval.PHOTO_MATCH_MARGIN] and its similarity is within
 * [VaultConfig.Retrieval.PHOTO_BEST_GAP] of the best kept one. Both were set with the models the
 * app ships, on the owner's phone (real matches cleared 0.056–0.150, searches no photo answers at
 * most 0.041), and checked on the gallery set (tools/visual-search): the right photo is still
 * listed for all 93 English and 93 Hindi searches.
 */
object PhotoGate {

    /** Words that describe any photo, in the languages searches are typed in. */
    val GENERIC = listOf(
        "a photo", "a picture", "an image", "a photo of something", "a random photo", "a snapshot",
        "एक तस्वीर", "फोटो", "ek photo",
    )

    /**
     * Hindi words a search in Latin letters is told by: on the benchmark none of 7,293 English
     * searches had one, and 99.9% of XM3600's romanised-Hindi ones did.
     */
    private val ROMANISED_HINDI = setOf(
        "mein", "ki", "ka", "ke", "ko", "se", "par", "pe", "wala", "wali", "wale", "aur", "hai", "hain",
        "tha", "thi", "hua", "hui", "hue", "raha", "rahi", "rahe", "karte", "karti", "karta", "kar", "liye",
        "sath", "saath", "upar", "neeche", "niche", "andar", "bahar", "ek", "bhi", "nahi", "kya", "yeh",
        "woh", "apne", "apni", "apna",
    )

    /**
     * The margin a photo must clear for [query]: the model reads Hindi typed in Latin letters least
     * well, and its right photos clear a much lower margin (half of them below 0.16, against 0.30 in
     * English), so that writing gets a lower one.
     */
    fun marginFor(query: String): Float {
        val q = query.lowercase()
        return when {
            q.any { it in 'ऀ'..'ॿ' } -> VaultConfig.Retrieval.PHOTO_MATCH_MARGIN_HINDI
            Regex("[a-z]+").findAll(q).any { it.value in ROMANISED_HINDI } -> VaultConfig.Retrieval.PHOTO_MATCH_MARGIN_ROMANISED
            else -> VaultConfig.Retrieval.PHOTO_MATCH_MARGIN
        }
    }

    /**
     * [candidates] (the most alike first) that are a match, most alike first. [vectorOf] gives a
     * candidate's stored vector; [generic] are the vectors of [GENERIC] from the query model.
     */
    fun keep(
        candidates: List<PhotoMatch>,
        generic: List<FloatArray>,
        vectorOf: (String) -> FloatArray?,
        margin: Float = VaultConfig.Retrieval.PHOTO_MATCH_MARGIN,
        gap: Float = VaultConfig.Retrieval.PHOTO_BEST_GAP,
    ): List<PhotoMatch> {
        if (generic.isEmpty()) return candidates
        val matches = candidates.filter { c ->
            val v = vectorOf(c.itemId) ?: return@filter false
            c.score - generic.maxOf { dot(it, v) } >= margin
        }
        val best = matches.maxOfOrNull { it.score } ?: return emptyList()
        return matches.filter { it.score >= best - gap }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
