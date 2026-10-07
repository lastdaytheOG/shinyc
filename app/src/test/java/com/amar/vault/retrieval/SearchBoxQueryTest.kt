package com.amar.vault.retrieval

import com.amar.vault.QueryPlanner
import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search as the search box runs it: the typed text goes through [QueryPlanner] first, and the
 * plan it makes decides what the retrieval service may return. The other retrieval tests hand
 * the service a bare query with no plan, which the app never does for typed text.
 *
 * Every item here was added to the vault just now, the way a file the user has only just
 * imported is: no date of its own, and a timestamp of the moment it was indexed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SearchBoxQueryTest {

    private val now = System.currentTimeMillis()

    private fun pdf(doc: String, file: String, text: String, timestamp: Long = now) = VaultItem(
        id = "${doc}_chunk0", uri = "content://docs/$doc", ocrText = text, lang = "en",
        itemType = ItemType.PDF, pageNum = 1, sourceFile = file, timestamp = timestamp,
        parentDocumentId = doc, chunkIndex = 0,
    )

    private val vault = listOf(
        pdf("archive", "archive.pdf", "summary of a matter closed long ago", timestamp = now - 400L * 24 * 60 * 60 * 1000),
        pdf("id", "Aadhaar_Card-2024.pdf", "government of india unique identification"),
        pdf("bill", "bijli.pdf", "electricity charges invoice for the quarter"),
        pdf("notes", "physics notes.pdf", "newton laws in the context of motion"),
        pdf("news", "Current Affairs October.pdf", "monthly current affairs summary"),
        pdf("old", "Current Affairs June.pdf", "an older current affairs summary"),
        pdf("marks", "marksheet.pdf", "statement of marks session 2023 roll number"),
        VaultItem(id = "shot", uri = "content://media/1", ocrText = "payment of rs 500 paid to ravi",
            lang = "en", itemType = ItemType.SCREENSHOT, sourceFile = "1", timestamp = now),
    )

    /** Mirrors Room; nothing in this vault carries date or classifier metadata. */
    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun allItemsSnapshot() = all
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    private object NoKeywordIndex : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = emptyList<String>()
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private object NoSemantic : SemanticRetriever {
        override val isReady = false
        override suspend fun embedQuery(query: String, trueLength: Boolean) = floatArrayOf()
        override suspend fun embedText(text: String) = floatArrayOf()
        override fun searchVectors(vector: FloatArray, k: Int) = emptyList<String>()
    }

    private val service = HybridSearchService(Repo(vault), NoKeywordIndex, NoSemantic, BoostConfig())

    /** What SearchViewModel sends for text typed into the search box. */
    private fun typed(text: String): List<String> = runBlocking {
        service.retrieve(RetrievalRequest.forResultList(text)).items.map { it.parentDocumentId ?: it.id }
    }

    private fun assertFinds(document: String, text: String) =
        assertTrue("typing \"$text\" must find $document, got ${typed(text)}", document in typed(text))

    @Test fun anOrdinaryWordFindsThePdf() = assertFinds("bill", "electricity")

    // ── Words that name a kind of document ──────────────────────────────────────────────

    @Test fun aadhaarFindsTheAadhaarPdf() = assertFinds("id", "aadhaar")
    @Test fun aadhaarCardFindsTheAadhaarPdf() = assertFinds("id", "aadhaar card")
    @Test fun invoiceFindsThePdfThatSaysInvoice() = assertFinds("bill", "invoice")
    @Test fun paymentFindsTheScreenshotThatSaysPayment() = assertFinds("shot", "payment")
    @Test fun paidFindsTheScreenshotThatSaysPaid() = assertFinds("shot", "paid to ravi")

    // ── Words that name a kind of file ──────────────────────────────────────────────────

    @Test fun aQueryEndingInPdfFindsThePdf() = assertFinds("notes", "physics pdf")
    @Test fun theFileNameAsWrittenFindsThePdf() = assertFinds("id", "Aadhaar_Card-2024.pdf")
    @Test fun pdfAloneListsPdfs() = assertFinds("notes", "pdf")
    @Test fun aWordThatOnlyContainsAFileKindIsAnOrdinaryWord() = assertFinds("notes", "context")

    // ── Words that name a date ──────────────────────────────────────────────────────────

    @Test fun aYearInTheFileNameFindsThePdf() = assertFinds("id", "aadhaar 2024")
    @Test fun aYearPrintedInThePdfFindsIt() = assertFinds("marks", "marksheet 2023")
    @Test fun aYearNothingMentionsDoesNotEmptyTheList() = assertFinds("bill", "electricity 2019")

    @Test
    fun aDateThatMatchesSomethingStillNarrowsTheList() {
        val today = typed("summary today")
        assertTrue("added today, says summary: $today", "news" in today)
        assertFalse("added 400 days ago: $today", "archive" in today)
    }

    @Test
    fun anAnswerIsNotBuiltFromOutsideThePeriodAsked() = runBlocking {
        // The ask path sends the plan as it is; only the result list drops an unmatched date.
        val plan = QueryPlanner.parse("electricity 2019")
        assertTrue(service.retrieve(RetrievalRequest(plan.cleanedQuery, plan)).items.isEmpty())
    }

    // ── Words that ask for the newest or oldest ─────────────────────────────────────────

    @Test
    fun currentAffairsListsEveryMatchNotOnlyTheNewest() {
        assertEquals(setOf("news", "old"), typed("current affairs").toSet())
    }
}
