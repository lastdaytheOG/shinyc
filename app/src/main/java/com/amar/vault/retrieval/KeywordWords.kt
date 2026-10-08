package com.amar.vault.retrieval

/**
 * What a word is to the keyword engine: a run of letters, combining marks and digits, in lower
 * case. Everything else — spaces, punctuation of any script, symbols — only separates words.
 *
 * It is decided here and not in the engine, so that a word is the same thing there as
 * everywhere else in the app (`[\p{L}\p{M}\p{N}]+`, in lower case) for any script. The engine
 * used to decide by itself and knew only English: "RÉSUMÉ" was not "résumé", and a Hindi word
 * followed by a danda, as the last word of every sentence is, was not that word.
 *
 * The decision is a [table] with one entry for each UTF-16 code unit. The native side is
 * given it once and does the cutting, a table look-up a character; [of] does the same here,
 * and is what the cutting there is checked against.
 *
 * Two things follow from deciding a character at a time, both far from the text this app is
 * for. A character outside the basic plane (a surrogate pair: emoji, historic scripts,
 * mathematical alphabets) separates words. And a capital is lowered by itself, not by the
 * word it is in, which differs from [String.lowercase] for two letters only: Turkish İ
 * becomes i, and a Greek capital sigma at the end of a word becomes σ rather than ς.
 */
object KeywordWords {

    private const val SEPARATES = '\u0000'

    /**
     * For each UTF-16 code unit: its lower case when it is part of a word, and NUL when it
     * only separates words.
     */
    val table: CharArray by lazy {
        // Built when the app starts, so it is not asked of Java one code unit at a time where
        // the answer is known for a whole block: Han ideographs and Hangul syllables are
        // letters with no capitals, and surrogates and private-use characters are never part
        // of a word. That is nearly three quarters of the table.
        val table = CharArray(Char.MAX_VALUE.code + 1)
        for (letters in LETTERS_WITHOUT_CAPITALS) for (code in letters) table[code] = code.toChar()
        for (asked in THE_REST) for (code in asked) table[code] = entryFor(code)
        table
    }

    /** What [table] holds for [code], asked of Java's character data. */
    internal fun entryFor(code: Int): Char = when (Character.getType(code).toByte()) {
        // The only kinds of character whose lower case is another character.
        Character.UPPERCASE_LETTER, Character.TITLECASE_LETTER, Character.LETTER_NUMBER ->
            Character.toLowerCase(code.toChar())
        Character.LOWERCASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
        Character.DECIMAL_DIGIT_NUMBER, Character.OTHER_NUMBER -> code.toChar()
        else -> SEPARATES
    }

    // Han ideographs (extension A and the main block, as far as each has always been
    // assigned) and Hangul syllables.
    private val LETTERS_WITHOUT_CAPITALS = listOf(0x3400..0x4DB5, 0x4E00..0x9FA5, 0xAC00..0xD7A3)
    // Everything else but surrogates (D800–DFFF) and private use (E000–F8FF), which stay NUL.
    private val THE_REST = listOf(0x0000..0x33FF, 0x4DB6..0x4DFF, 0x9FA6..0xABFF, 0xD7A4..0xD7FF, 0xF900..0xFFFF)

    /** The words of [text], lowercased, with one space between them. */
    fun of(text: String): String {
        val table = table
        val out = CharArray(text.length)
        var n = 0
        var wordEnded = false
        for (c in text) {
            val lowered = table[c.code]
            if (lowered != SEPARATES) {
                if (wordEnded) {
                    out[n++] = ' '
                    wordEnded = false
                }
                out[n++] = lowered
            } else if (n > 0) {
                wordEnded = true
            }
        }
        return String(out, 0, n)
    }
}
