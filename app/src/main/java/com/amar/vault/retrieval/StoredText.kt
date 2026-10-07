package com.amar.vault.retrieval

/**
 * A row's stored text is its page — what was read from the document or off the picture —
 * followed by a line of tags in square brackets that the indexers add:
 *
 *     …where its total turnover or the gross receipt in the previous year
 *     [pdf document invoice billing receipt]
 *
 * The tags are there so that a payment screenshot is found by "receipt". They are worked out
 * from fragments of the page ("rs" anywhere on it is enough for "receipt payment bill
 * invoice"), so a row that has a typed word only among its tags does not say that word.
 */
internal object StoredText {

    /** Where the page ends in [stored]: the start of the tag line, or its length when it has none. */
    fun endOfPage(stored: String): Int {
        if (!stored.endsWith("]")) return stored.length
        val tagLine = stored.lastIndexOf("\n[")
        return if (tagLine < 0) stored.length else tagLine
    }

    fun page(stored: String): String = stored.substring(0, endOfPage(stored))

    /** The tags, separated by spaces; empty when there are none. */
    fun tags(stored: String): String {
        val end = endOfPage(stored)
        return if (end == stored.length) "" else stored.substring(end + 2, stored.length - 1)
    }
}
