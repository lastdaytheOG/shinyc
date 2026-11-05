package com.amar.vault.indexing

/**
 * The Hindi reader takes 8 and 0 for the Bengali digits that look like them: "০৪/08/2025" for
 * 08/08/2025. Stored that way a date or an amount cannot be found by typing it, an answer read off
 * the page cannot see it as a number, and the same line from the two readers looks like two lines.
 *
 * Measured on 104 scanned pages with known text: where the page is known, ৪ stood for 8 on 33 of
 * 36 lines and ০ for 0 on 23 of 25; the rest were letters in the middle of a word, "g" and "o". So
 * these two are put right unless they touch a letter. The app reads English and Hindi, where neither
 * can be a real digit. The other Bengali digits came up once each, too few to say what they stand
 * for, and Devanagari digits stay: "२०२५" is what a Hindi page says.
 */
internal object LookAlikeDigits {
    private const val ZERO = '০'
    private const val EIGHT = '৪'
    private val STANDING_ALONE = Regex("(?<![\\p{L}\\p{M}])[$ZERO$EIGHT](?![\\p{L}\\p{M}])")

    fun repaired(read: String): String =
        if (ZERO !in read && EIGHT !in read) read
        else STANDING_ALONE.replace(read) { if (it.value[0] == ZERO) "0" else "8" }
}
