package com.amar.vault.retrieval

import com.amar.vault.StashItemWithVaultItem
import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import com.amar.vault.oneCardPerDocument
import com.amar.vault.queryWordsOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hindi in the search box: a Devanagari word has to find the document that contains it, name
 * the page it is on, and show the words around it — exactly as an English word does.
 *
 * The keyword engine is left empty here, so these go through the scan lanes, which is the path
 * a Hindi word takes whenever the engine has no whole-word match for it (a word that ends at a
 * danda, "जाए।", is one token to the engine). The engine itself is covered on a device by
 * HindiSearchDeviceTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HindiSearchTest {

    private fun page(doc: String, pdfPage: Int, text: String, file: String = "$doc.pdf") = VaultItem(
        id = "${doc}_chunk${pdfPage - 1}", uri = "content://docs/$doc", ocrText = text, lang = "hi",
        itemType = ItemType.PDF, pageNum = pdfPage, sourceFile = file, timestamp = 0L,
        parentDocumentId = doc, chunkIndex = pdfPage - 1,
    )

    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun allItemsSnapshot() = all
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    private object NoKeywordEngine : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = emptyList<String>()
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private object NoSemantic : SemanticRetriever {
        override val isReady = false
        override suspend fun embedQuery(query: String, trueLength: Boolean) = floatArrayOf()
        override suspend fun embedText(text: String) = floatArrayOf()
        override fun searchVectors(vector: FloatArray, k: Int) = emptyList<String>()
    }

    // One word, "qanoon", spelt the two ways Unicode allows. Stored text is NFC, in which its
    // first letter is KA (U+0915) followed by a nukta (U+093C); a keyboard may send the single
    // code point QA (U+0958) instead.
    private val restOfWord = "ानून"
    private val storedAsTwo = "यह " + 0x0915.toChar() + 0x093C.toChar() + restOfWord
    private val typedAsOne = 0x0958.toChar() + restOfWord

    /** A circular about the use of Hindi in an office, a training syllabus, and an English paper. */
    private val corpus = listOf(
        page("circular", 1, "संस्थान में विभिन्न विभागों के सरकारी कामकाज में राजभाषा हिंदी के प्रयोग की समीक्षा की जाए।"),
        page("circular", 2, "सभी अधिकारी अपने अनुभाग के आंकड़ों को निर्धारित कॉलम में भरकर भेजें।"),
        page("circular", 3, "विषयः तिमाही प्रगति रिपोर्ट दिनांक 01.01.2025 से 31.03.2025 तक। राजभाषा अधिनियम के अंतर्गत जारी कागजात दुविभाषी हों।"),
        page("syllabus", 1, "भारत सरकार कौशल विकास और उद्यमिता मंत्रालय प्रशिक्षण महानिदेशालय योग्यता आधारित पाठ्यक्रम"),
        page("paper", 1, "A structure for nucleic acid has already been proposed by Pauling and Corey."),
        page("identity", 1, "Unique Identification Authority of India", file = "आधार_कार्ड.pdf"),
        page("statute", 1, storedAsTwo + " पूरे भारत में लागू होगा"),
    )

    private fun VaultItem.asRow() = StashItemWithVaultItem(
        stashId = "search_$id", sessionId = null, vaultItemId = id, vaultType = "SAVED", category = "",
        savedAt = timestamp, sourceApp = "", isFavorite = false, createdAt = timestamp, userNote = null,
        thumbnailPath = null, uri = uri, ocrText = ocrText, itemType = itemType, sourceFile = sourceFile,
        timestamp = timestamp, title = title, mimeType = mimeType,
    )

    /** What the search box shows for [typed]: document, page it opens on, words shown. */
    private fun search(typed: String) = runBlocking {
        val service = HybridSearchService(Repo(corpus), NoKeywordEngine, NoSemantic, BoostConfig())
        val items = service.retrieve(RetrievalRequest.forResultList(typed)).items
        oneCardPerDocument(items.map { it.asRow() }, items, emptyMap(), queryWordsOf(typed))
            .map { Triple(corpus.first { c -> c.id == it.row.vaultItemId }.parentDocumentId, it.page, it.excerpt) }
    }

    @Test
    fun aHindiWordFindsItsDocumentAndThePageItIsOn() {
        val (document, page, excerpt) = search("प्रशिक्षण").first()
        assertEquals("syllabus", document)
        assertEquals(1, page)
        assertTrue(excerpt, excerpt!!.contains("प्रशिक्षण"))
    }

    @Test
    fun theBestPageIsTheOneWithMostOfTheWords() {
        val (document, page, excerpt) = search("प्रगति रिपोर्ट").first()
        assertEquals("circular", document)
        assertEquals("both words are on page 3 only", 3, page)
        assertTrue(excerpt, excerpt!!.contains("प्रगति रिपोर्ट"))
    }

    @Test
    fun aWordThatEndsAtADandaIsFound() {
        val (document, page, _) = search("जाए").first()
        assertEquals("circular", document)
        assertEquals(1, page)
    }

    @Test
    fun aWordOnSeveralPagesGivesOneCardForTheDocument() {
        val hits = search("राजभाषा")
        assertEquals(listOf("circular"), hits.map { it.first })
    }

    @Test
    fun aHindiFileNameFindsTheFile() {
        val (document, page, excerpt) = search("आधार").first { it.first == "identity" }
        assertEquals("identity", document)
        assertNull("found by its name: there is no page to open on", page)
        assertNull(excerpt)
    }

    @Test
    fun hindiAndEnglishWordsWorkInOneQuery() {
        assertEquals(setOf("syllabus", "paper"), search("मंत्रालय nucleic").map { it.first }.toSet())
    }

    @Test
    fun aLetterTypedAsOneCodePointMatchesTextStoredAsTwo() {
        // The keyboard may send क़ as the single code point U+0958; stored text is NFC, where
        // the same letter is क followed by the nukta U+093C.
        val (document, page, _) = search(typedAsOne).first()
        assertEquals("statute", document)
        assertEquals(1, page)
    }

    @Test
    fun anEnglishWordDoesNotDragInTheHindiDocuments() {
        assertEquals(listOf("paper"), search("nucleic").map { it.first })
    }
}
