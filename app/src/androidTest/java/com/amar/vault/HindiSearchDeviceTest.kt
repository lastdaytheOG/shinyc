package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.SearchableName
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hindi in the search box against the REAL stack on a device or emulator: Room, the native
 * keyword engine and the app's own retrieval service — the parts HindiSearchTest (JVM) replaces
 * with fakes. The engine splits on whitespace and lower-cases ASCII only, so whether a
 * Devanagari word is one of its tokens, and what happens to a word that ends at a danda, can
 * only be seen here.
 *
 * Seeds its own pages (ids start with [PREFIX]) and removes them afterwards. Run it with
 * `adb shell am instrument`; Gradle's `connectedDebugAndroidTest` uninstalls the app when it
 * finishes, which deletes everything the app has indexed on that device.
 */
@RunWith(AndroidJUnit4::class)
class HindiSearchDeviceTest {

    private companion object {
        const val PREFIX = "hinditest-"
    }

    private val services by lazy {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java)
    }

    private fun page(doc: String, pdfPage: Int, text: String, file: String = "$doc.pdf") = VaultItem(
        id = "$PREFIX${doc}_chunk${pdfPage - 1}", uri = "content://hinditest/$doc", ocrText = text, lang = "hi",
        itemType = ItemType.PDF, pageNum = pdfPage, sourceFile = file, timestamp = 1_700_000_000_000L + pdfPage,
        parentDocumentId = "$PREFIX$doc", chunkIndex = pdfPage - 1,
    )

    // "qanoon" spelt the two ways Unicode allows: stored text is NFC, where its first letter is
    // KA (U+0915) + nukta (U+093C); a keyboard may send the single code point QA (U+0958).
    private val restOfWord = "ानून"
    private val storedAsTwo = "" + 0x0915.toChar() + 0x093C.toChar() + restOfWord
    private val typedAsOne = "" + 0x0958.toChar() + restOfWord

    private val corpus = listOf(
        page("circular", 1, "संस्थान में विभिन्न विभागों के सरकारी कामकाज में राजभाषा हिंदी के प्रयोग की समीक्षा की जाए।"),
        page("circular", 2, "सभी अधिकारी अपने अनुभाग के आंकड़ों को निर्धारित कॉलम में भरकर भेजें।"),
        page("circular", 3, "विषयः तिमाही प्रगति रिपोर्ट दिनांक 01.01.2025 से 31.03.2025 तक। राजभाषा अधिनियम के अंतर्गत जारी कागजात दुविभाषी हों।"),
        page("syllabus", 1, "भारत सरकार कौशल विकास और उद्यमिता मंत्रालय प्रशिक्षण महानिदेशालय योग्यता आधारित पाठ्यक्रम"),
        page("identity", 1, "Unique Identification Authority of India", file = "आधार_कार्ड.pdf"),
        page("statute", 1, "यह $storedAsTwo पूरे भारत में लागू होगा"),
    )

    @Before
    fun seed() = runBlocking {
        services.database().vaultDao().insertAll(corpus)
        // The same text the indexer gives the engine: the page, then the document's name.
        corpus.forEach { services.bm25Index().addDocument(it.id, "${it.ocrText} ${SearchableName.of(it)}") }
    }

    @After
    fun remove() = runBlocking {
        services.database().openHelper.writableDatabase
            .execSQL("DELETE FROM vault_items WHERE id LIKE '$PREFIX%'")
    }

    private fun VaultItem.asRow() = StashItemWithVaultItem(
        stashId = "search_$id", sessionId = null, vaultItemId = id, vaultType = "SAVED", category = "",
        savedAt = timestamp, sourceApp = "", isFavorite = false, createdAt = timestamp, userNote = null,
        thumbnailPath = null, uri = uri, ocrText = ocrText, itemType = itemType, sourceFile = sourceFile,
        timestamp = timestamp, title = title, mimeType = mimeType,
    )

    /** What the search box shows for [typed], among this test's own documents. */
    private fun search(typed: String): List<SearchHit> = runBlocking {
        val items = services.retrievalService().retrieve(RetrievalRequest.forResultList(typed)).items
        oneCardPerDocument(items.map { it.asRow() }, items, emptyMap(), queryWordsOf(typed))
            .filter { it.row.vaultItemId.startsWith(PREFIX) }
    }

    private fun SearchHit.document() = corpus.first { it.id == row.vaultItemId }.parentDocumentId!!.removePrefix(PREFIX)

    @Test
    fun theKeywordEngineKnowsADevanagariWord() {
        val ids = services.lexicalRetriever().bm25("प्रशिक्षण", 300)
        assertTrue("engine hits: $ids", "${PREFIX}syllabus_chunk0" in ids)
    }

    @Test
    fun aHindiWordOpensOnThePageItIsOn() {
        val hit = search("प्रशिक्षण").first()
        assertEquals("syllabus", hit.document())
        assertEquals(1, hit.page)
        assertTrue(hit.excerpt, hit.excerpt!!.contains("प्रशिक्षण"))
    }

    @Test
    fun twoWordsOpenOnThePageThatHasBoth() {
        val hit = search("प्रगति रिपोर्ट").first()
        assertEquals("circular", hit.document())
        assertEquals(3, hit.page)
    }

    @Test
    fun aWordThatEndsAtADandaIsFound() {
        val hit = search("जाए").first { it.document() == "circular" }
        assertEquals(1, hit.page)
    }

    @Test
    fun aHindiFileNameFindsTheFile() {
        val hit = search("कार्ड").first { it.document() == "identity" }
        assertNull("found by its name: there is no page to open on", hit.page)
    }

    @Test
    fun aLetterTypedAsOneCodePointMatchesTextStoredAsTwo() {
        val hit = search(typedAsOne).first()
        assertEquals("statute", hit.document())
        assertEquals(1, hit.page)
    }
}
