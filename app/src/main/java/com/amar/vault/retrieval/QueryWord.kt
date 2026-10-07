package com.amar.vault.retrieval

/**
 * A word of a query as it is looked for in stored text: as typed, and — when it was typed with
 * punctuation at either end, as in `claude?`, `(queue)` or `invoice,` — without it. Looked for
 * only as typed, such a word is one the vault does not have, however many pages say it.
 *
 * Punctuation inside a word (`node.js`, `2026-27`) is part of the word and is left alone.
 * Expects the word in lower case, like the text it is compared with.
 */
internal class QueryWord(val typed: String) {

    /** [typed] without the punctuation at its ends; [typed] itself when that leaves no word. */
    val bare: String = typed.replace(EDGE_PUNCTUATION, "").takeIf { it.length >= 2 } ?: typed

    private val hasBare = bare != typed

    fun isIn(loweredText: String): Boolean =
        loweredText.contains(typed) || (hasBare && loweredText.contains(bare))

    /** Whether it is in [loweredText] before position [end] — on the page, not among its tags. */
    fun isIn(loweredText: String, end: Int): Boolean =
        endsBy(loweredText, typed, end) || (hasBare && endsBy(loweredText, bare, end))

    private fun endsBy(text: String, word: String, end: Int): Boolean {
        val at = text.indexOf(word)
        return at >= 0 && at + word.length <= end
    }

    companion object {
        // A combining mark is part of a word: most Devanagari words end in one.
        private val EDGE_PUNCTUATION = Regex("^[^\\p{L}\\p{M}\\p{N}]+|[^\\p{L}\\p{M}\\p{N}]+$")
        private val WHITESPACE = Regex("\\s+")

        /** The words of [loweredQuery], in the order typed. */
        fun of(loweredQuery: String): List<QueryWord> =
            loweredQuery.split(WHITESPACE).filter { it.isNotEmpty() }.map(::QueryWord)
    }
}
