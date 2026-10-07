package com.amar.vault.retrieval

/**
 * How many of a query's words stand next to each other, in the order they were typed, in a
 * piece of text. "last working days" is all there in "Last Working Day Commencement" (a run of
 * three), half there in "within 3 to 5 working days" (a run of two), and not there at all in a
 * page that has the three words in three different paragraphs.
 *
 * A word may be followed by a plural ending it was not typed with, or be typed with one the
 * text lacks: "days" finds "day" and "queue" finds "queues". Anything that is not a letter, a
 * combining mark or a digit separates two words, so line breaks, commas and hyphens between
 * them do not break a run.
 *
 * [queryWords] and the text handed to [longestRun] are expected in lower case.
 */
internal class WordsInOrder(queryWords: List<String>) {

    private val words: List<String> = queryWords.take(MAX_WORDS)

    /** The number of words a full run has. */
    val size: Int get() = words.size

    // One pattern per run of words, built the first time that run is looked for.
    private val patterns = HashMap<Long, Regex>()

    /** The longest run found, 0 when no two of the words stand together. */
    fun longestRun(loweredText: String): Int {
        if (words.size < 2 || loweredText.isEmpty()) return 0
        for (length in words.size downTo 2) {
            for (start in 0..words.size - length) {
                if (pattern(start, length).containsMatchIn(loweredText)) return length
            }
        }
        return 0
    }

    private fun pattern(start: Int, length: Int): Regex =
        patterns.getOrPut(start.toLong() shl 32 or length.toLong()) {
            Regex(
                NOT_AFTER_A_WORD +
                    words.subList(start, start + length).joinToString(BETWEEN_WORDS) { anyNumber(it) } +
                    NOT_BEFORE_A_WORD
            )
        }

    /** [word] as typed, or as its singular or plural. Only plain Latin words are varied. */
    private fun anyNumber(word: String): String {
        if (word.length < 3 || !word.all { it in 'a'..'z' }) return Regex.escape(word)
        val singular = if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word
        return Regex.escape(singular) + "(?:s|es)?"
    }

    private companion object {
        /** A longer query is judged by its first words: the patterns grow with the square of this. */
        const val MAX_WORDS = 8
        const val WORD_CHARS = "\\p{L}\\p{M}\\p{N}"
        const val NOT_AFTER_A_WORD = "(?<![$WORD_CHARS])"
        const val NOT_BEFORE_A_WORD = "(?![$WORD_CHARS])"
        const val BETWEEN_WORDS = "[^$WORD_CHARS]+"
    }
}
