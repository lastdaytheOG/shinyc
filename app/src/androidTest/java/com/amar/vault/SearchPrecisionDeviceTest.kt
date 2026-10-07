package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalTuning
import com.amar.vault.retrieval.SearchableName
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reports made from a phone on 2026-10-07, against the REAL stack: Room, the native
 * keyword engine with its own typo fallback, and the app's retrieval service. SearchPrecisionTest
 * (JVM) covers the same ground with an imitation of the engine; whether the real one's
 * look-alikes are kept out, and whether capitals reach it unchanged, is only seen here.
 *
 * Seeds its own pages (ids start with [PREFIX]) and removes them afterwards. Run it with
 * `adb shell am instrument`; Gradle's `connectedDebugAndroidTest` uninstalls the app when it
 * finishes, which deletes everything the app has indexed on that device.
 */
@RunWith(AndroidJUnit4::class)
class SearchPrecisionDeviceTest {

    private companion object {
        const val PREFIX = "precisiontest-"
    }

    private val services by lazy {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java)
    }

    private fun page(doc: String, pdfPage: Int, text: String, file: String, tags: String = "") = VaultItem(
        id = "$PREFIX${doc}_chunk${pdfPage - 1}", uri = "content://precisiontest/$doc", ocrText = text, lang = "en",
        itemType = ItemType.PDF, pageNum = pdfPage, sourceFile = file, timestamp = 1_700_000_000_000L + pdfPage,
        tags = tags, parentDocumentId = "$PREFIX$doc", chunkIndex = pdfPage - 1,
    )

    private fun screenshot(id: String, text: String) = VaultItem(
        id = "$PREFIX$id", uri = "content://media/external/images/media/$id", ocrText = text, lang = "en",
        itemType = ItemType.SCREENSHOT, sourceFile = id, timestamp = 1_700_000_000_500L,
    )

    // The words are made up where they need to be in no other document on the device.
    private val corpus = listOf(
        page("newsletter", 1, "This week in Zylaude Code: build your own mods, a wrap-up allowance", "Gmail - This week in Zylaude Code.pdf"),
        // Every three-letter piece of "vrendaxa", and not the word. Seeded before the page that
        // says it, which is the order the engine breaks a tie in.
        page("book", 3, "vren enda daxa", "Operating Systems 9th edition.pdf"),
        page("book", 7, "and my Nicolette Avi To Vrendaxan and Ellen, and Barbara, Anne and Harold", "Operating Systems 9th edition.pdf"),
        // All three words of "zlast zorking zays", no two of them together.
        page("book", 453, "the zylause holds for zyloud storage. the zlast fault of the zorking set over many zays", "Operating Systems 9th edition.pdf"),
        page("syllabus", 30, "each zylause of the zyloud unit. vrendaza calendar and agenda", "Syllabus 2025-27.pdf"),
        page("receipt", 1, "updated within 3 to 5 zorking zays. this is only a confirmation", "receipt_NB26.pdf"),
        page("calendar", 1, "First Mid Term Second Mid Term Zlast Zorking Zay Commencement of Practical Exams", "Academic Calendar.pdf"),
        screenshot("player", "18:09 ELLA CIAO. zylaude playing next"),
        // Between them, "zylab" and "laudx" have four of the six three-letter pieces of "zylaudde".
        page("notes", 2, "zylab results and laudx figures", "Lab notes.pdf"),
        // As the indexer stores a page: its text, and the tags it gave it. Page 2 is short and
        // only tagged with the word, which is what the engine ranks highest; page 9 says it.
        page("ledger", 2, "sums due", "Ledger.pdf", tags = "pdf document zinvoice zbilling"),
        page("ledger", 9, "upon production of the zinvoice the assessing officer shall, within thirty days of the end " +
            "of the month in which it is received, pass an order in writing and serve a copy of it", "Ledger.pdf", tags = "pdf document"),
        page("memo", 1, "a short memo about nothing", "Memo.pdf", tags = "pdf document zinvoice"),
    )

    @Before
    fun seed() = runBlocking {
        services.database().vaultDao().insertAll(corpus)
        corpus.forEach { services.bm25Index().addDocument(it.id, "${it.ocrText} ${it.tags} ${SearchableName.of(it)}") }
    }

    @After
    fun remove() = runBlocking {
        services.database().openHelper.writableDatabase
            .execSQL("DELETE FROM vault_items WHERE id LIKE '$PREFIX%'")
    }

    /** This test's documents and pictures listed for [typed], best first. */
    private fun typed(typed: String, chip: String = SearchFilter.ALL): List<String> = runBlocking {
        val only: ((VaultItem) -> Boolean)? =
            if (chip == SearchFilter.ALL) null else { item -> SearchFilter.accepts(chip, item, saved = null) }
        services.retrievalService().retrieve(RetrievalRequest.forResultList(typed, only)).items.documents()
    }

    /** The same, as the list was before 2026-10-07: what the tests below are measured against. */
    private fun typedBefore(typed: String, tuning: RetrievalTuning): List<String> = runBlocking {
        services.retrievalService().retrieve(RetrievalRequest(typed, tuning = tuning)).items.documents()
    }

    /** The page this test's [document] is listed by for [typed]; null when it is not listed. */
    private fun pageOf(document: String, typed: String, tuning: RetrievalTuning? = null): Int? = runBlocking {
        val request = if (tuning == null) RetrievalRequest.forResultList(typed) else RetrievalRequest(typed, tuning = tuning)
        services.retrievalService().retrieve(request).items
            .firstOrNull { it.parentDocumentId == "$PREFIX$document" }?.pageNum
    }

    private fun List<VaultItem>.documents() = map { it.parentDocumentId ?: it.id }
        .filter { it.startsWith(PREFIX) }
        .map { it.removePrefix(PREFIX) }
        .distinct()

    @Test
    fun aWordThatIsInTheVaultBringsNoLookAlikes() {
        // "zylause" and "zyloud" are one and two letters from "zylaude".
        assertEquals(setOf("newsletter", "player"), typed("zylaude").toSet())
    }

    @Test
    fun aWordFoundInsideALongerWordBringsNoLookAlikes() {
        // As "brenda" was: in the vault only inside "Brendan", so the engine, which knows whole
        // words, finds none and falls back to near spellings — here "vrendaza".
        assertEquals(listOf("book"), typed("vrendaxa"))
    }

    @Test
    fun aDocumentIsNamedByThePageThatSaysTheWord() {
        assertEquals(7, pageOf("book", "vrendaxa"))
        // The control: the engine itself names the page that only has the pieces, and with
        // both rules off that is the page the document is listed by.
        val before = RetrievalTuning(typoHelpOnlyForMissingWords = false, pageWordsBeforeTags = false)
        assertEquals(3, pageOf("book", "vrendaxa", before))
    }

    @Test
    fun aPageThatSaysTheWordComesBeforeOneOnlyTaggedWithIt() {
        assertEquals("the document is listed by the page that says it", 9, pageOf("ledger", "zinvoice"))
        assertEquals("and before the one that is only tagged with it", listOf("ledger", "memo"), typed("zinvoice"))
        // The control: the engine names the short page that only has the tag.
        val before = RetrievalTuning(typoHelpOnlyForMissingWords = false, pageWordsBeforeTags = false)
        assertEquals(2, pageOf("ledger", "zinvoice", before))
    }

    @Test
    fun withoutTheRuleTheLookAlikesAreListed() {
        // The control. If these stopped failing the old way, the two tests above would pass
        // whatever the rule did.
        val before = RetrievalTuning(typoHelpOnlyForMissingWords = false)
        assertTrue(typedBefore("zylaude", before).toString(), "book" in typedBefore("zylaude", before))
        assertTrue(typedBefore("vrendaxa", before).toString(), "syllabus" in typedBefore("vrendaxa", before))
    }

    @Test
    fun aMisspeltWordStillFindsWhatItWasMeantToBe() {
        assertTrue(typed("zylaudde").toString(), "newsletter" in typed("zylaudde"))
    }

    @Test
    fun aMisspeltWordListsNearSpellingsAndNotWhatOnlySharesPiecesWithIt() {
        // The engine names a page for sharing enough three-letter pieces with a word it does
        // not know, wherever on the page they are. That is a candidate, not a spelling.
        val listed = typed("zylaudde")
        assertFalse("got $listed", "notes" in listed)
        val before = typedBefore("zylaudde", RetrievalTuning(typoHelpOnlyForMissingWords = false))
        assertTrue("the control — the engine does name it: $before", "notes" in before)
    }

    @Test
    fun punctuationTypedOntoAWordChangesNothing() {
        assertEquals(typed("zylaude"), typed("zylaude?"))
        assertEquals(typed("vrendaxa"), typed("(vrendaxa)"))
    }

    @Test
    fun wordsInTheOrderTypedComeFirst() {
        val listed = typed("zlast zorking zays")
        assertEquals("got $listed", "calendar", listed.first())
        assertTrue("got $listed", listed.indexOf("receipt") in 1 until listed.indexOf("book"))
    }

    @Test
    fun withoutTheBonusTheDocumentThatSaysItIsNotFirst() {
        // The control for the test above: the book has every word, the calendar the phrase.
        val listed = typedBefore("zlast zorking zays", RetrievalTuning(wordOrderBonus = false))
        assertEquals("got $listed", "book", listed.first())
    }

    @Test
    fun capitalsMakeNoDifference() {
        for (query in listOf("zylaude", "vrendaxa", "zlast zorking zays", "zylaudde")) {
            val expected = typed(query)
            assertFalse("\"$query\" must find something to compare", expected.isEmpty())
            assertEquals(expected, typed(query.uppercase()))
            assertEquals(expected, typed(query.replaceFirstChar { it.uppercase() }))
        }
    }

    @Test
    fun theChipsNarrowTheList() {
        assertEquals(listOf("player"), typed("zylaude", SearchFilter.IMAGES))
        assertEquals(listOf("newsletter"), typed("zylaude", SearchFilter.DOCUMENTS))
    }
}
