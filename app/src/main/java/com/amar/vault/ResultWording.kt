package com.amar.vault

/**
 * Whether a search result says what was typed: one of the query's words, standing as a word of
 * its own, in the result's text, name, tags or note. A result listed only for a longer word that
 * contains the typed one ("catalogue", "category" for "cat"), or only for the query's small words
 * ("in", "the" of "dog in the snow"), does not; the search screen shows those after, folded away.
 *
 * It decides where a result is shown, never whether it was found: the order the search gave stays.
 */
internal object ResultWording {

    /** Small words a search is not about, as the app's users write them: English, Hindi, Hindi in Latin letters. */
    private val SMALL = setOf(
        "a", "an", "the", "in", "on", "of", "at", "to", "for", "with", "and", "or", "is", "are", "was",
        "by", "from", "as", "it", "its", "this", "that", "my", "me",
        "में", "का", "की", "के", "को", "से", "पर", "और", "है", "हैं", "एक",
        "mein", "ka", "ki", "ke", "ko", "se", "par", "pe", "aur", "hai", "hain", "ek",
    )

    /** The words of [query] a result has to say: all but the small ones, or all when that is all there is. */
    fun contentWords(query: String): List<String> {
        val words = queryWordsOf(query).filter { it.isNotBlank() }.distinct()
        return words.filter { it !in SMALL }.ifEmpty { words }
    }

    /** True when [hit] says one of [words] as a word of its own. */
    fun saysAWord(hit: SearchHit, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val row = hit.row
        return listOfNotNull(hit.excerpt, row.ocrText, row.title, row.sourceFile, row.tags, row.userNote)
            .any { text -> UnicodeText.nfc(text).lowercase().let { lower -> words.any { containsAsWord(lower, it) } } }
    }

    /** [word] stands in [text] with no letter, digit or vowel sign joined to either side of it. */
    fun containsAsWord(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val i = text.indexOf(word, from)
            if (i < 0) return false
            val joinedBefore = i > 0 && isPartOfAWord(text[i - 1])
            val end = i + word.length
            val joinedAfter = end < text.length && isPartOfAWord(text[end])
            if (!joinedBefore && !joinedAfter) return true
            from = i + 1
        }
    }

    /** A letter or digit, or a mark joined to one (a Devanagari vowel sign, a nukta, a virama). */
    private fun isPartOfAWord(c: Char): Boolean {
        if (Character.isLetterOrDigit(c)) return true
        val type = Character.getType(c)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }
}
