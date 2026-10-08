package com.amar.vault.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the keyword engine is handed as the words of a text or a query. */
class KeywordWordsTest {

    @Test
    fun wordsAreLoweredAndPunctuationOnlySeparatesThem() {
        assertEquals("hello world 2026 27 node js", KeywordWords.of("  Hello, World! (2026-27) node.js "))
        assertEquals("aadhaar card 2024 pdf", KeywordWords.of("Aadhaar_Card-2024.pdf"))
    }

    @Test
    fun nothingButPunctuationHasNoWords() {
        assertEquals("", KeywordWords.of(""))
        assertEquals("", KeywordWords.of(" …—•₹ \n\t?!"))
    }

    @Test
    fun capitalsOfAnyScriptAreLowered() {
        // The engine lowered A to Z only: "RÉSUMÉ" was "rÉsumÉ", a different word from "résumé".
        assertEquals("résumé москва ελλάδα", KeywordWords.of("RÉSUMÉ Москва ΕΛΛΆΔΑ"))
        assertEquals(KeywordWords.of("résumé"), KeywordWords.of("RÉSUMÉ"))
    }

    @Test
    fun aHindiWordIsItselfWhateverFollowsIt() {
        // A danda ends the sentence. The engine cut at spaces and ASCII punctuation only, so
        // the last word of every Hindi sentence was kept with its danda on, as another word.
        val sentence = "राजभाषा हिंदी के प्रयोग की समीक्षा की जाए।"
        assertEquals("राजभाषा हिंदी के प्रयोग की समीक्षा की जाए", KeywordWords.of(sentence))
        // Vowel signs and the virama are combining marks: they are part of the word.
        assertEquals("प्रशिक्षण", KeywordWords.of("“प्रशिक्षण”"))
    }

    @Test
    fun punctuationThatIsNotAsciiSeparatesToo() {
        assertEquals("it s total 500 paid", KeywordWords.of("it’s — total: ₹500 • paid…"))
        // A no-break space and a zero-width space are not part of a word.
        assertEquals("net amount", KeywordWords.of("net amount​"))
    }

    @Test
    fun aWordIsWhatTheRestOfTheAppCallsAWord() {
        // The definition used for query words, searchable names and highlights.
        val word = Regex("[\\p{L}\\p{M}\\p{N}]+")
        val texts = listOf(
            "Figure 6.7 Multilevel feedback queues. processes in queue 0",
            "विषयः तिमाही प्रगति रिपोर्ट दिनांक 01.01.2025 से 31.03.2025 तक।",
            "x² + ½ of α/β — naïve café №5 ①",
            "snake_case camelCase kebab-case 3.14 1,00,000 user@example.com",
            "क़ानून क़ानून ज़िंदगी",          // precomposed and combining nukta
            "tab\tnew\nline\r\nend",
        )
        for (text in texts) {
            assertEquals(text, word.findAll(text.lowercase()).joinToString(" ") { it.value }, KeywordWords.of(text))
        }
    }

    @Test
    fun theTableIsWhatJavaSaysOfEveryCharacter() {
        // The table is filled a block at a time where the answer is known; this is the plain
        // definition, asked one character at a time: a letter, a combining mark or a number
        // is part of a word, in lower case; anything else, and half a surrogate pair, is not.
        val partOfAWord = setOf(
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
            Character.MODIFIER_LETTER, Character.OTHER_LETTER,
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        )
        val table = KeywordWords.table
        assertEquals(65536, table.size)
        for (code in 0..0xFFFF) {
            val c = code.toChar()
            val expected = if (!c.isSurrogate() && Character.getType(code).toByte() in partOfAWord)
                Character.toLowerCase(c) else '\u0000'
            assertEquals("U+%04X".format(code), expected, table[code])
        }
    }

    @Test
    fun whatIsDecidedACharacterAtATime() {
        // Outside the basic plane a character is two code units; it separates words.
        assertEquals("paid in full abc", KeywordWords.of("paid😀in𝒜full abc"))
        // A capital is lowered by itself. For these two letters that is not String.lowercase.
        assertEquals("istanbul", KeywordWords.of("İstanbul"))
        assertEquals("οδοσ", KeywordWords.of("ΟΔΟΣ"))
    }
}
