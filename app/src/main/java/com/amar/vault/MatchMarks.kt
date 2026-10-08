package com.amar.vault

/**
 * Which words of a shown text are the ones a query found: the one place that decides, for the
 * excerpt on a result card and for the page a result opens on.
 *
 * A typed word is found where it stands, also inside a longer word, as search finds it
 * ("queue" in "Queues"). It is also found as the same word in another form: typing "days"
 * finds a page that says "Last Working Day", and the card used to mark "Last" and "Working"
 * and leave "Day" plain, as if the page did not say it.
 */
internal object MatchMarks {

    /** Endings a typed word may have that the word on the page does not. Longest first. */
    private val ENDINGS = listOf(
        "ations", "ation", "ities", "ments", "ings", "ions", "ment", "ness", "able", "less",
        "ing", "ion", "ers", "ity", "ful", "ies", "'s", "’s", "es", "ed", "er", "s",
    )
    /** What a word on the page may end in beyond the root and still be the same word. */
    private val SMALL_ENDINGS = listOf("", "s", "es", "e", "'s", "’s", "ed", "ing", "y")
    private const val MIN_ROOT = 3

    private fun isPartOfAWord(c: Char): Boolean = c.isLetterOrDigit() || when (Character.getType(c).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /**
     * [word] without each ending it could be said to have: "days" gives "day", "booking"
     * gives "book", "planned" gives "plann" and "plan", "copies" gives "cop" and "copy".
     */
    fun rootsOf(word: String): List<String> {
        val lowered = word.lowercase()
        val roots = LinkedHashSet<String>()
        for (ending in ENDINGS) {
            if (!lowered.endsWith(ending)) continue
            val root = lowered.dropLast(ending.length)
            if (root.length < MIN_ROOT) continue
            roots += root
            // "planned" is "plan" with its last letter doubled; "copies" is "copy".
            if (root.length > MIN_ROOT && root[root.length - 1] == root[root.length - 2]) roots += root.dropLast(1)
            if (ending == "ies") roots += root + "y"
        }
        return roots.toList()
    }

    /** True when [pageWord], one word as it stands on a page, is [queryWord] in another form. */
    private fun isAFormOf(pageWord: String, roots: List<String>): Boolean =
        roots.any { root -> pageWord.startsWith(root) && pageWord.substring(root.length) in SMALL_ENDINGS }

    /**
     * The stretches of [text] to mark for [queryWords], in order and not overlapping.
     * [queryWords] are as typed, in any case.
     */
    fun ranges(text: String, queryWords: List<String>): List<IntRange> {
        val words = queryWords.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        if (words.isEmpty() || text.isEmpty()) return emptyList()
        val found = ArrayList<IntRange>()
        // 1. The word as typed, wherever it stands.
        for (word in words) {
            var at = text.indexOf(word, ignoreCase = true)
            while (at >= 0) {
                found += at until at + word.length
                at = text.indexOf(word, at + word.length, ignoreCase = true)
            }
        }
        // 2. The same word in another form, as a word of its own.
        val roots = words.filter { it.length > MIN_ROOT }.flatMap(::rootsOf).distinct()
        if (roots.isNotEmpty()) {
            var i = 0
            while (i < text.length) {
                while (i < text.length && !isPartOfAWord(text[i]) && text[i] != '\'' && text[i] != '’') i++
                val start = i
                while (i < text.length && (isPartOfAWord(text[i]) || text[i] == '\'' || text[i] == '’')) i++
                if (i > start && isAFormOf(text.substring(start, i).lowercase(), roots)) found += start until i
            }
        }
        return merged(found)
    }

    /**
     * True when [pageWord] — one word read off a page, with whatever punctuation is on it —
     * is to be marked for [queryWords].
     */
    fun says(pageWord: String, queryWords: List<String>): Boolean = ranges(pageWord, queryWords).isNotEmpty()

    private fun merged(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size < 2) return ranges
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        var current = sorted[0]
        for (next in sorted.drop(1)) {
            current = if (next.first <= current.last + 1) current.first..maxOf(current.last, next.last)
            else { out += current; next }
        }
        out += current
        return out
    }
}

/** A word read off a rendered page: what it says and where it is, in the picture's own pixels. */
internal data class WordOnPage(
    val text: String,
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    /** Which line of the page it is on, counted in reading order. */
    val line: Int,
)

/** Where on a page the match is: every word to mark, and the one to bring into view. */
internal data class PageMatch(val marks: List<WordOnPage>, val focus: WordOnPage?) {
    companion object { val NONE = PageMatch(emptyList(), null) }
}

/**
 * Finds a query's words among the words read off a page.
 *
 * A page can say a word many times. The one a result was listed for is the one in the words
 * the result card showed ([excerpt]); that is the one to bring into view, and it is found by
 * the line that shares the most words with the excerpt.
 */
internal object PageMatches {
    private val NOT_A_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    fun locate(words: List<WordOnPage>, queryWords: List<String>, excerpt: String): PageMatch {
        val marks = words.filter { MatchMarks.says(it.text, queryWords) }
        if (marks.isEmpty()) return PageMatch.NONE
        val excerptWords = excerpt.lowercase().split(NOT_A_WORD).filterTo(HashSet()) { it.length >= 3 }
        if (excerptWords.isEmpty()) return PageMatch(marks, marks.first())
        // How much of the excerpt each line, with the line after it, says.
        val byLine = words.groupBy { it.line }
        fun shared(line: Int): Int = (byLine[line].orEmpty() + byLine[line + 1].orEmpty() + byLine[line - 1].orEmpty())
            .count { word -> word.text.lowercase().split(NOT_A_WORD).any { it in excerptWords } }
        val focus = marks.maxByOrNull { shared(it.line) * 1000 - it.line } ?: marks.first()
        return PageMatch(marks, focus)
    }
}
