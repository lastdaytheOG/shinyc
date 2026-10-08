package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.retrieval.FuzzyMatcher
import com.amar.vault.retrieval.KeywordText
import com.amar.vault.retrieval.KeywordWords
import kotlinx.coroutines.runBlocking
import com.amar.vault.retrieval.NativeBm25Index
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyword engine as the app uses it — text in, ids out, through the real native library —
 * on an engine of this test's own: nothing of the app's vault or its engine is touched.
 *
 * The engine's own rules (ranking, removal, cleaning up) are tested without the app around it
 * by `tools/vault-check/engine_test.py`. This is about what the two sides do together: what a
 * word is, in any script; and that what the engine calls a near spelling is what the rest of
 * the app calls one.
 */
@RunWith(AndroidJUnit4::class)
class KeywordEngineDeviceTest {

    private val app by lazy { InstrumentationRegistry.getInstrumentation().targetContext.applicationContext }
    private val index = NativeBm25Index()

    @After
    fun free() = index.shutdown()

    private fun add(vararg items: Pair<String, String>) = items.forEach { (id, text) -> index.addDocument(id, text) }

    @Test
    fun theNativeSideCutsWordsExactlyAsKotlinSaysTheyAreCut() {
        // The cutting is done natively, by a table; KeywordWords.of is what it has to equal.
        val samples = listOf(
            "", "   ", "Hello, World! (2026-27) node.js", "RÉSUMÉ Москва ΕΛΛΆΔΑ straße İstanbul",
            "राजभाषा हिंदी के प्रयोग की समीक्षा की जाए। “प्रशिक्षण”", "it’s — total: ₹500 • paid…",
            "x² + ½ of α/β — naïve café №5 ①", "emoji😀paid 𝒜𝒷𝒸 mixed", "tab\tnew\nline\r\nend",
        )
        // And everything the device has stored, as the engine is given it.
        val stored = runBlocking { VaultDatabase.get(app).vaultDao().getAll() }.map { KeywordText.of(it) }
        for (text in samples + stored) {
            assertEquals(text.take(80), KeywordWords.of(text), NativeSearchEngine.wordsOf(text))
        }
    }

    @Test
    fun theTableIsWhatThisDeviceSaysOfEveryCharacter() {
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
    fun capitalsOfAnyScriptMakeNoDifference() {
        add("cv" to "Curriculum vitae — RÉSUMÉ of the applicant", "city" to "Москва, столица России", "other" to "nothing here")
        for (typed in listOf("résumé", "RÉSUMÉ", "Résumé")) assertEquals(typed, listOf("cv"), index.search(typed))
        for (typed in listOf("москва", "МОСКВА", "Москва")) assertEquals(typed, listOf("city"), index.search(typed))
        // English as before.
        for (typed in listOf("applicant", "APPLICANT", "Applicant")) assertEquals(typed, listOf("cv"), index.search(typed))
    }

    @Test
    fun aHindiWordIsFoundWhateverPunctuationFollowsIt() {
        // The last word of a Hindi sentence has a danda after it.
        add(
            "circular" to "संस्थान में सरकारी कामकाज में राजभाषा हिंदी के प्रयोग की समीक्षा की जाए।",
            "syllabus" to "भारत सरकार “कौशल विकास” और उद्यमिता मंत्रालय",
        )
        assertEquals(listOf("circular"), index.search("जाए"))
        assertEquals(listOf("syllabus"), index.search("विकास"))
        // Part of a word, as "brenda" in "Brendan": the engine did this by three-byte pieces,
        // and one Devanagari letter is three bytes.
        assertEquals(listOf("circular"), index.search("कामका"))
    }

    @Test
    fun punctuationOfAnyKindOnlySeparatesWords() {
        add("bill" to "Total: ₹500 • paid—in full… it’s done", "name" to "Aadhaar_Card-2024.pdf")
        assertEquals(listOf("bill"), index.search("500"))
        assertEquals(listOf("bill"), index.search("paid"))
        assertEquals(listOf("bill"), index.search("“full”"))
        assertEquals(listOf("name"), index.search("card"))
        assertEquals(listOf("name"), index.search("(aadhaar)"))
    }

    @Test
    fun anItemThatIsRemovedIsNoLongerFound() {
        add("old-page" to "the old wording of the clause", "new-page" to "the new wording of the clause")
        assertEquals(setOf("old-page", "new-page"), index.search("wording").toSet())
        assertTrue(index.removeDocument("old-page"))
        assertEquals(listOf("new-page"), index.search("wording"))
        assertEquals(emptyList<String>(), index.search("old"))
        assertFalse("there is nothing left to remove", index.removeDocument("old-page"))
        assertEquals(1L, index.size().items)
    }

    @Test
    fun aWordKnownOnlyInsideALongerWordFindsThatWordAndNotLoosePieces() {
        // The report of 2026-10-07: "brenda" brought up a calendar. The engine then named any
        // item that shared 40% of the word's three-letter pieces, wherever in it they were.
        add(
            "dedication" to "To Brendan and Ellen, and Barbara, Anne and Harold",
            "calendar" to "bren rend enda — break, current, standard; calendar and agenda",
        )
        assertEquals(listOf("dedication"), index.search("brenda"))
        assertEquals(listOf("dedication"), index.search("Brendan"))
        assertEquals("as it is typed, letter by letter", listOf("dedication"), index.search("brend"))
    }

    @Test
    fun aNearSpellingIsWhatTheRestOfTheAppCallsOne() {
        // One item to a word, so the items found are the words found.
        val words = listOf(
            "invoice", "invoices", "receipt", "receipts", "payment", "claude", "clause", "cloud", "queue", "queen",
            "quote", "working", "worker", "calendar", "calender", "semester", "marksheet", "aadhaar", "aadhar",
            "booking", "book", "books", "install", "installation", "execute", "plan", "plane", "planet", "bank",
            "banks", "tank", "thank", "cart", "card", "care", "core", "form", "from", "farm", "firm", "list", "last",
            "lost", "least", "passport", "password", "licence", "license", "address", "adress",
        )
        words.forEach { index.addDocument(it, it) }
        val typed = listOf(
            "invoce", "invioce", "recipt", "reciept", "paymnt", "cluade", "claudde", "qeue", "quueue", "wroking",
            "calandar", "semster", "marksheat", "aadhhar", "bookings", "installing", "executing", "planned",
            "bnak", "crat", "fomr", "lsit", "pasport", "pasword", "lisence", "addrses", "xyzzy", "zzzzzzzz",
        )
        for (word in typed) {
            assertFalse("$word is meant to be a word the vault does not have", words.any { word in it })
            val matcher = FuzzyMatcher(listOf(word), roots = true)
            assertEquals("near spellings of \"$word\"", words.filter { matcher.matches(it) }.toSet(), index.search(word).toSet())
        }
    }

    @Test
    fun aWordThatIsInTheVaultBringsNoNearSpellings() {
        add("says-it" to "Claude Code", "near" to "cloud storage, and a clause", "longer" to "Claudette Colbert")
        assertEquals(listOf("says-it"), index.search("claude"))
    }
}
