package com.amar.vault.retrieval

/**
 * A word of a query as it is looked for in stored text: as typed, and — when it was typed with
 * punctuation at either end, as in `claude?`, `(queue)` or `invoice,` — without it. Looked for
 * only as typed, such a word is one the vault does not have, however many pages say it.
 *
 * Punctuation inside a word (`node.js`, `2026-27`) is part of the word and is left alone.
 * Expects the word in lower case, like the text it is compared with.
 */
internal class QueryWord private constructor(val typed: String, private val phrase: Regex?) {

    constructor(typed: String) : this(typed, null)

    /** [typed] without the punctuation at its ends; [typed] itself when that leaves no word. */
    val bare: String = if (phrase != null) typed
    else typed.replace(EDGE_PUNCTUATION, "").takeIf { it.length >= 2 } ?: typed

    private val hasBare = bare != typed
    /** The first word of a phrase: a text without it cannot have the phrase. */
    private val firstOfPhrase = if (phrase != null) typed.substringBefore(' ') else ""

    fun isIn(loweredText: String): Boolean =
        if (phrase != null) loweredText.contains(firstOfPhrase) && phrase.containsMatchIn(loweredText)
        else loweredText.contains(typed) || (hasBare && loweredText.contains(bare))

    /** Whether it is in [loweredText] before position [end] — on the page, not among its tags. */
    fun isIn(loweredText: String, end: Int): Boolean =
        if (phrase != null) {
            loweredText.contains(firstOfPhrase) && (phrase.find(loweredText)?.let { it.range.last < end } ?: false)
        } else endsBy(loweredText, typed, end) || (hasBare && endsBy(loweredText, bare, end))

    /**
     * Whether it stands in [loweredText] before position [end] as a word of its own: with no
     * letter, mark or digit on either side of it. "act" stands alone in "the Finance Act" and
     * does not in "action". A phrase stands alone wherever it is found.
     */
    fun standsAloneIn(loweredText: String, end: Int = loweredText.length): Boolean {
        if (phrase != null) return isIn(loweredText, end)
        return aloneBefore(loweredText, typed, end) || (hasBare && aloneBefore(loweredText, bare, end))
    }

    private fun aloneBefore(text: String, word: String, end: Int): Boolean {
        var at = text.indexOf(word)
        while (at >= 0 && at + word.length <= end) {
            val before = at == 0 || !isPartOfAWord(text[at - 1])
            val after = at + word.length == text.length || !isPartOfAWord(text[at + word.length])
            if (before && after) return true
            at = text.indexOf(word, at + 1)
        }
        return false
    }

    private fun isPartOfAWord(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun endsBy(text: String, word: String, end: Int): Boolean {
        val at = text.indexOf(word)
        return at >= 0 && at + word.length <= end
    }

    companion object {
        // A combining mark is part of a word: most Devanagari words end in one.
        private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{M}\\p{N}]+|[^\\p{L}\\p{M}\\p{N}]+$")
        private val WHITESPACE = Regex("\\s+")

        /**
         * Several words that count only when they stand together, in this order: what an
         * abbreviation stands for ("one time password"). Between them there may be a space, a
         * line break or a hyphen ("one-time password"); nothing else. Taken a word at a time,
         * such a meaning listed every page that says "one" or "time".
         */
        fun phrase(loweredPhrase: String): QueryWord {
            val words = loweredPhrase.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            val pattern = words.joinToString("[\\s\\-]+") { Regex.escape(it) }
            return QueryWord(words.joinToString(" "), Regex("(?<![\\p{L}\\p{M}\\p{N}])$pattern"))
        }

        /** The words of [loweredQuery], in the order typed. */
        fun of(loweredQuery: String): List<QueryWord> =
            loweredQuery.split(WHITESPACE).filter { it.isNotEmpty() }.map(::QueryWord)
    }
}
