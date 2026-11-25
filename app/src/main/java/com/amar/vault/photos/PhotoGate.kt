package com.amar.vault.photos

import com.amar.vault.VaultConfig

/**
 * Which of the photos most like a query are really a match, so that a search for something no photo
 * shows lists no photos instead of the closest 24 of whatever is there.
 *
 * A photo's similarity to a query is not a probability: one photo is close to every query and
 * another to none. Use the calibrated fixed bar across the whole index so a match does not vanish
 * when unrelated photos are added. The generic prompts provide a query-independent reference for
 * how much better the query describes a photo than words that describe any photo.
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

    fun strength(score: Float, margin: Float): Float =
        score + VaultConfig.Retrieval.PHOTO_STRENGTH_MARGIN_WEIGHT * margin

    fun matches(
        index: PhotoIndex,
        query: FloatArray,
        margin: Float = VaultConfig.Retrieval.PHOTO_MATCH_MARGIN,
        only: ((String) -> Boolean)? = null,
    ): List<PhotoMatch> {
        // Evaluate both fixed bars against every vector; neither tier depends on vault size.
        val found = index.scanMatches(query, only) { score, m ->
            if (index.generic.isEmpty()) score >= VaultConfig.Retrieval.PHOTO_POSSIBLE_STRENGTH
            else m >= margin && strength(score, m) >= VaultConfig.Retrieval.PHOTO_POSSIBLE_STRENGTH
        }
        return found.matches.map { match ->
            match.copy(possible = strength(match.score, match.margin) < VaultConfig.Retrieval.PHOTO_SURE_STRENGTH)
        }
            .sortedWith(compareByDescending<PhotoMatch> { strength(it.score, it.margin) }.thenBy { it.itemId })
    }
}
