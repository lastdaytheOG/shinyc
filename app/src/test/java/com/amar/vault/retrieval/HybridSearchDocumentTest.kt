package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Documents in search: a PDF is stored as one row per chunk, and it has to be findable — by
 * the words inside it, by its file name, and even when a much longer document matches the same
 * query on more pages than there are result slots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HybridSearchDocumentTest {

    private fun chunk(doc: String, index: Int, text: String, file: String = "$doc.pdf", timestamp: Long = 0L) =
        VaultItem(
            id = "${doc}_chunk$index", uri = "content://docs/$doc", ocrText = text, lang = "en",
            itemType = ItemType.PDF, pageNum = index + 1, sourceFile = file, timestamp = timestamp,
            parentDocumentId = doc, chunkIndex = index,
        )

    /** Mirrors Room: the snapshot keeps the order given; an id lookup comes back in id order. */
    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun allItemsSnapshot() = all
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    private class Lexical(private val ranked: List<String>) : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = ranked.take(limit)
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private object NoSemantic : SemanticRetriever {
        override val isReady = false
        override suspend fun embedQuery(query: String, trueLength: Boolean) = floatArrayOf()
        override suspend fun embedText(text: String) = floatArrayOf()
        override fun searchVectors(vector: FloatArray, k: Int) = emptyList<String>()
    }

    private fun service(corpus: List<VaultItem>, bm25: List<String> = emptyList()) =
        HybridSearchService(Repo(corpus), Lexical(bm25), NoSemantic, BoostConfig())

    private fun List<VaultItem>.documents() = map { it.parentDocumentId ?: it.id }

    // ── A long document must not take every slot ────────────────────────────────────────

    /** An 80-page book and three one-page PDFs that all mention the query; the book is newest. */
    private val book = (0 until 80).map { chunk("book", it, "invoice terms on page $it", timestamp = 9_000L) }
    private val small = listOf("rent", "phone", "school").map { chunk(it, 0, "invoice for $it", timestamp = 1_000L) }
    private val crowded = book + small
    private val crowdedBm25 = crowded.map { it.id }

    @Test
    fun aLongDocumentLeavesRoomForTheOtherDocuments() = runBlocking {
        val result = service(crowded, crowdedBm25).retrieve(RetrievalRequest("invoice"))
        assertEquals(setOf("book", "rent", "phone", "school"), result.items.documents().toSet())
        assertEquals("each document appears once", 4, result.items.size)
    }

    @Test
    fun perPageResultsAreTheBehaviourThatHidTheOtherDocuments() = runBlocking {
        val perPage = RetrievalTuning(onePerDocument = false)
        val result = service(crowded, crowdedBm25).retrieve(RetrievalRequest("invoice", tuning = perPage))
        assertEquals(50, result.items.size)
        assertEquals("the book's pages fill every slot", setOf("book"), result.items.documents().toSet())
    }

    @Test
    fun everyLaneNamesADocumentByTheSameRow() = runBlocking {
        // The keyword engine prefers page 2; the scan lanes meet page 1 first.
        val doc = listOf(chunk("policy", 0, "refund terms"), chunk("policy", 1, "refund refund policy"))
        val result = service(doc, bm25 = listOf("policy_chunk1")).retrieve(RetrievalRequest("refund"))
        assertEquals(listOf("policy_chunk1"), result.items.map { it.id })
    }

    // ── Found by name ───────────────────────────────────────────────────────────────────

    private val named = listOf(
        chunk("id", 0, "government of india unique identification", file = "Aadhaar_Card-2024.pdf"),
        chunk("id", 1, "address and date of birth", file = "Aadhaar_Card-2024.pdf"),
        chunk("bill", 0, "electricity charges for march", file = "bijli.pdf"),
        VaultItem(id = "photo", uri = "content://media/aadhaar.jpg", ocrText = "a street at night",
            lang = "en", itemType = ItemType.PHOTO, sourceFile = "aadhaar.jpg", timestamp = 0L),
    )

    @Test
    fun aPdfIsFoundByItsFileName() = runBlocking {
        // Nothing in the keyword index and no page says "aadhaar": only the file name does.
        val result = service(named).retrieve(RetrievalRequest("aadhaar card"))
        assertEquals(listOf("id"), result.items.documents())
    }

    @Test
    fun aMisspeltFileNameStillFindsThePdf() = runBlocking {
        // "aadhar" is not a substring of "aadhaar": only the typo lane can match it.
        val result = service(named).retrieve(RetrievalRequest("aadhar"))
        assertEquals(listOf("id"), result.items.documents())
    }

    @Test
    fun anImageIsNotMatchedByItsGeneratedFileName() = runBlocking {
        val result = service(named).retrieve(RetrievalRequest("aadhaar"))
        assertFalse("photo" in result.items.map { it.id })
    }

    @Test
    fun anItemIsFoundByItsTitle() = runBlocking {
        val saved = VaultItem(id = "link", uri = "https://example.com/a", ocrText = "terms between the parties",
            lang = "en", itemType = ItemType.LINK, timestamp = 0L, title = "Flat rent agreement")
        val result = service(listOf(saved)).retrieve(RetrievalRequest("rent agreement"))
        assertEquals(listOf("link"), result.items.map { it.id })
    }

    @Test
    fun aDocumentNamedAfterTheQueryIsFoundBesideTextMatches() = runBlocking {
        val corpus = listOf(
            chunk("notes", 0, "remember to carry the passport copy", file = "notes.pdf"),
            chunk("pp", 0, "republic of india type p country code ind", file = "passport.pdf"),
        )
        // The keyword index only knows the page that mentions the word.
        val result = service(corpus, bm25 = listOf("notes_chunk0")).retrieve(RetrievalRequest("passport"))
        assertEquals(setOf("notes", "pp"), result.items.documents().toSet())
    }

    // ── SearchableName ──────────────────────────────────────────────────────────────────

    @Test
    fun searchableNameKeepsTheSpellingAndAddsTheWords() {
        assertEquals("Aadhaar_Card-2024.pdf Aadhaar Card 2024 pdf",
            SearchableName.of(ItemType.PDF, "Aadhaar_Card-2024.pdf", null))
        assertEquals("resume", SearchableName.of(ItemType.WORD, "resume", null))
        assertEquals("a document with no name has none", "", SearchableName.of(ItemType.PDF, "", null))
        assertEquals("an image's file name is not a name", "", SearchableName.of(ItemType.PHOTO, "IMG_0042.jpg", null))
        assertEquals("a title counts for any item", "Flat rent agreement",
            SearchableName.of(ItemType.LINK, "", "Flat rent agreement"))
    }

    @Test
    fun searchableNameDoesNotSplitDevanagariWords() {
        // Vowel signs are combining marks, not letters; splitting on them would shred the word.
        assertEquals("विद्यालय_सूचना.pdf विद्यालय सूचना pdf", SearchableName.of(ItemType.PDF, "विद्यालय_सूचना.pdf", null))
    }
}
