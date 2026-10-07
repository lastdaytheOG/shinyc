package com.amar.vault.retrieval

/**
 * The fuzzy lane's typo predicate: does any whitespace-delimited word (≥ 3 chars) of a text
 * lie within the typo tolerance of any query word? Tolerance is 1 edit for query words of up
 * to 4 chars and 2 edits for longer ones, and a text word is only compared when its length is
 * within that tolerance of the query word's.
 *
 * This is exactly what the lane computed before as
 * `text.split(Regex("\\s+")).filter { it.length >= 3 }` plus a full Levenshtein distance per
 * candidate pair — the answer for every (query, text) is unchanged. What changed is the cost:
 * the text is scanned in place (no token strings, no lists), and the edit distance stops as
 * soon as it can no longer come in under the tolerance. On a query with no match the lane
 * visits every word of every item, so that cost is the whole worst case.
 *
 * With [roots], a text word is also accepted when a query word is that word with an ending
 * put on it: "book" for "booking", "install" for "installation", "plan" for "planned",
 * "execute" for "executing". Those are further apart than two edits, and are what a vault has
 * in place of a word it has in no other form. Off by default: the lane's answer is then the
 * former one, exactly.
 *
 * Not thread-safe (it reuses its distance rows): build one per lane call.
 */
internal class FuzzyMatcher(queryWords: List<String>, private val roots: Boolean = false) {

    private val words: List<String> = queryWords
    private val tolerance = IntArray(words.size) { if (words[it].length <= 4) 1 else 2 }

    // For each query word, how long it is without each ending it could be said to have.
    private val rootLengths: Array<IntArray> = Array(words.size) { w ->
        if (!roots) IntArray(0)
        else ENDINGS.filter { words[w].endsWith(it) }.map { words[w].length - it.length }.toIntArray()
    }

    private var prev = IntArray(48)
    private var curr = IntArray(48)

    fun matches(text: String): Boolean = indexOfMatch(text) >= 0

    /** The first word of [text] that [matches] would accept, or null. */
    fun firstMatch(text: String): String? {
        val start = indexOfMatch(text)
        if (start < 0) return null
        var end = start
        while (end < text.length && !WHITESPACE_TABLE[text[end].code]) end++
        return text.substring(start, end)
    }

    /** Where the first word that [matches] would accept begins, or -1. */
    fun indexOfMatch(text: String): Int {
        val whitespace = WHITESPACE_TABLE
        val n = text.length
        var i = 0
        while (i < n) {
            while (i < n && whitespace[text[i].code]) i++
            val start = i
            while (i < n && !whitespace[text[i].code]) i++
            val length = i - start
            if (length < 3) continue
            for (w in words.indices) {
                val max = tolerance[w]
                val diff = length - words[w].length
                if (diff <= max && -diff <= max && withinDistance(words[w], text, start, length, max)) return start
                if (rootLengths[w].isNotEmpty() && isRootOf(w, text, start, length)) return start
            }
        }
        return -1
    }

    /**
     * True iff query word [w] is `text[start, start + length)` with an ending on it — the word
     * itself ("book", booking), the word less its final e ("execute", executing), or the word
     * with its last letter doubled ("plan", planned). A root of under [MIN_ROOT] letters is
     * the beginning of too many unrelated words to count.
     */
    private fun isRootOf(w: Int, text: String, start: Int, length: Int): Boolean {
        // The word without what follows it on the page: a comma, a full stop, a bracket.
        var n = length
        while (n > 0 && !isPartOfAWord(text[start + n - 1])) n--
        if (n < MIN_ROOT) return false
        val query = words[w]
        for (stem in rootLengths[w]) {
            val same = when (n) {
                stem -> stem
                stem + 1 -> if (text[start + n - 1] == 'e') stem else continue
                stem - 1 -> if (stem >= 2 && query[stem - 1] == query[stem - 2]) n else continue
                else -> continue
            }
            if (query.regionMatches(0, text, start, same)) return true
        }
        return false
    }

    private fun isPartOfAWord(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /** True iff the edit distance between [query] and `text[start, start + length)` is ≤ [max]. */
    private fun withinDistance(query: String, text: String, start: Int, length: Int, max: Int): Boolean {
        if (prev.size <= length) {
            prev = IntArray(length + 16)
            curr = IntArray(length + 16)
        }
        for (j in 0..length) prev[j] = j
        for (i in 1..query.length) {
            curr[0] = i
            var rowMin = i
            val qc = query[i - 1]
            for (j in 1..length) {
                val cost = if (qc == text[start + j - 1]) 0 else 1
                val v = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
                curr[j] = v
                if (v < rowMin) rowMin = v
            }
            // Row minima never decrease from one row to the next, so the final distance
            // (a cell of the last row) cannot be under a row minimum already past the limit.
            if (rowMin > max) return false
            val swap = prev; prev = curr; curr = swap
        }
        return prev[length] <= max
    }

    private companion object {
        const val MIN_ROOT = 4

        /**
         * The endings a root is looked for under. One- and two-letter endings put straight on
         * a word are within the edit tolerance already; "ed" and "er" are here for the words
         * that double a letter or drop an e before them.
         */
        val ENDINGS = listOf(
            "ing", "ings", "ed", "er", "ers", "ion", "ions", "ation", "ations",
            "ment", "ments", "ness", "able", "ity", "ities", "ful", "less",
        )

        /**
         * The lane has always tokenized with the regex `\s+`. This is that same regex's
         * verdict for every UTF-16 code unit, so the in-place scan splits exactly where the
         * regex would on whatever runtime it is on — the desktop JVM and Android do not agree
         * on which characters `\s` covers, and hard-coding either set would change results
         * on the other.
         */
        val WHITESPACE_TABLE: BooleanArray by lazy {
            val whitespace = Regex("\\s")
            val one = CharArray(1)
            BooleanArray(Char.MAX_VALUE.code + 1) { code ->
                one[0] = code.toChar()
                whitespace.matches(String(one))
            }
        }
    }
}
