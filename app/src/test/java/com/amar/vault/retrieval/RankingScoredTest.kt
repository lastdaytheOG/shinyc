package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import com.amar.vault.benchmark.BenchmarkCase
import com.amar.vault.benchmark.BenchmarkContentType
import com.amar.vault.benchmark.RetrievalEvaluator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two ranking rules that came out of scoring the starter golden queries on 2026-10-08,
 * each with its control (the switch off is the behaviour before), and the scorer itself: it
 * scores what the search box runs, it knows a document by what the file is called, and it no
 * longer skips every document that was picked from the phone's files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class RankingScoredTest {

    private fun page(doc: String, file: String, text: String, at: Long) = VaultItem(
        id = "${doc}_chunk0", uri = "content://docs/$doc", ocrText = text, lang = "en",
        itemType = ItemType.PDF, pageNum = 1, sourceFile = file, timestamp = at,
        parentDocumentId = doc, chunkIndex = 0,
    )

    private fun screenshot(id: String, text: String, at: Long) = VaultItem(
        id = id, uri = "content://media/$id", ocrText = text, lang = "en",
        itemType = ItemType.SCREENSHOT, sourceFile = id, timestamp = at,
    )

    /** Hands rows over newest first, as the vault does. */
    private class Repo(all: List<VaultItem>) : SearchRepository {
        private val newestFirst = all.sortedByDescending { it.timestamp }
        override suspend fun getByIds(ids: List<String>) = newestFirst.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun allItemsSnapshot() = newestFirst
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

    private fun service(vault: List<VaultItem>) = HybridSearchService(Repo(vault), NoKeywordIndex, NoSemantic, BoostConfig())

    private fun order(vault: List<VaultItem>, typed: String, tuning: RetrievalTuning = RetrievalTuning()): List<String> = runBlocking {
        val request = RetrievalRequest.forResultList(typed)
        service(vault).retrieve(request.copy(tuning = tuning.copy(relaxEmptyFilter = true))).items.map { it.parentDocumentId ?: it.id }
    }

    // ── A word in what the file is called ───────────────────────────────────────────────

    /** The book, and a later screenshot of a search screen that shows the book's name. */
    private val bookAndScreenshot = listOf(
        page("book", "Silberschatz Operating System Concepts.pdf", "operating system concepts ninth edition abraham silberschatz", at = 1_000),
        screenshot("shot", "see everything about queue silberschatz 9th edition.pdf document opens at page 4", at = 2_000),
    )

    @Test
    fun typingAFilesNameListsThatFileFirst() {
        assertEquals(listOf("book", "shot"), order(bookAndScreenshot, "silberschatz"))
    }

    @Test
    fun beforeAScreenshotThatShowsTheNameStoodAboveTheFile() {
        assertEquals(listOf("shot", "book"), order(bookAndScreenshot, "silberschatz", RetrievalTuning(nameBonus = false)))
    }

    @Test
    fun theNameDoesNotOutweighAPageThatSaysThePhrase() {
        val vault = listOf(
            page("calendar", "Academic Calendar.pdf", "commencement of classes 20 july", at = 1_000),
            page("notice", "notice.pdf", "the academic calendar committee met; the last working day is 27 november", at = 2_000),
        )
        assertEquals("the page that says the three words together", "notice", order(vault, "last working day").first())
    }

    // ── A word standing on its own ──────────────────────────────────────────────────────

    /** The older page says "Act"; the newer one only has those letters inside "action" and "factor". */
    private val actAndAction = listOf(
        page("law", "gazette.pdf", "the finance act received the assent of the president", at = 1_000),
        page("plan", "minutes.pdf", "the action plan lists every factor the committee weighed", at = 2_000),
    )

    @Test
    fun aRowWhereTheWordStandsAloneComesBeforeOneWhereItIsInsideAnother() {
        assertEquals(listOf("law", "plan"), order(actAndAction, "act"))
    }

    @Test
    fun beforeRowsThatTiedWereLeftNewestFirst() {
        assertEquals(listOf("plan", "law"), order(actAndAction, "act", RetrievalTuning(wholeWordsFirst = false)))
    }

    @Test
    fun aWordInsideALongerWordIsStillFound() {
        // "brenda" in "Brendan": wanted, and kept. It is only placed after a row that says the word itself.
        val vault = listOf(
            page("letter", "letter.pdf", "yours sincerely, brenda", at = 1_000),
            page("book", "book.pdf", "as brendan gregg has shown", at = 2_000),
        )
        assertEquals(listOf("letter", "book"), order(vault, "brenda"))
    }

    @Test
    fun aQueryWordKnowsWhenItStandsAlone() {
        assertTrue(QueryWord("act").standsAloneIn("the finance act, 2026"))
        assertTrue(QueryWord("act").standsAloneIn("act"))
        assertTrue(!QueryWord("act").standsAloneIn("the action plan and every factor"))
        assertTrue("the page ends before the tags begin", !QueryWord("act").standsAloneIn("an action\nact", end = "an action".length))
        assertTrue(QueryWord("रुपये").standsAloneIn("चार सौ रुपये"))
    }

    // ── The scorer ──────────────────────────────────────────────────────────────────────

    /** A picked document has pages and no row of its own; a picture is its own row. */
    private val vault = listOf(
        page("book", "Silberschatz Operating System Concepts.pdf", "operating system concepts ninth edition", at = 1_000),
        page("law", "gazette.pdf", "the finance act received the assent of the president", at = 1_500),
        screenshot("shot", "vikram traders paid 250", at = 2_000).copy(title = "probe_pay.png"),
    )

    private fun case(files: List<String>, vararg queries: String) = BenchmarkCase(
        id = "c", contentType = BenchmarkContentType.PDF, queries = queries.toList(), expectedFiles = files, kind = "phrase",
    )

    @Test
    fun aGoldenQueryCanNameItsAnswerByFileName() = runBlocking {
        val scored = RetrievalEvaluator(service(vault), Repo(vault))
            .scoreAll(listOf(case(listOf("gazette.pdf"), "finance act"), case(listOf("probe_pay.png"), "vikram traders")))

        assertEquals(0, scored.skipped)
        assertEquals(listOf(1, 1), scored.scores.map { it.rank })
        assertEquals(listOf("gazette.pdf", "probe_pay.png"), scored.scores.map { it.top })
        assertEquals(listOf("phrase", "phrase"), scored.scores.map { it.kind })
        assertEquals(1.0, scored.scores.first().rightOnTop, 1e-9)
    }

    @Test
    fun aPickedDocumentIsScoredNotSkipped() = runBlocking {
        val repo = Repo(vault)
        // The control: asked for as a row, the way the scorer looked for it before, a picked
        // document is not there — and so every golden query about one was skipped.
        assertTrue(repo.getByIds(listOf("law")).isEmpty())

        val byId = BenchmarkCase(
            id = "c", contentType = BenchmarkContentType.PDF, documentId = "law",
            queries = listOf("finance act"), expectedResults = listOf("law"),
        )
        val scored = RetrievalEvaluator(service(vault), repo).scoreAll(listOf(byId))
        assertEquals(0, scored.skipped)
        assertEquals(1, scored.scores.single().rank)
    }

    @Test
    fun aCaseAboutAFileThatIsNotInThisVaultIsSkippedAndSaysSo() = runBlocking {
        val scored = RetrievalEvaluator(service(vault), Repo(vault)).scoreAll(listOf(case(listOf("not-here.pdf"), "anything")))
        assertEquals(1, scored.skipped)
        assertTrue(scored.scores.isEmpty())
        assertTrue(scored.rows.single().getValue("reason").contains("not-here.pdf"))
    }

    @Test
    fun whatIsScoredIsWhatTheSearchBoxRuns() = runBlocking {
        var sent: RetrievalRequest? = null
        val recording = object : RetrievalService {
            override suspend fun retrieve(request: RetrievalRequest): RetrievalResult {
                sent = request
                return RetrievalResult(emptyList())
            }
        }
        RetrievalEvaluator(recording, Repo(vault)).scoreAll(listOf(case(listOf("gazette.pdf"), "finance act 2019")))

        val request = sent!!
        assertNotNull("the typed text was read first", request.plan)
        assertEquals("finance act", request.query)
        assertEquals("finance act 2019", request.wordsIfFilterEmpty)
        assertTrue("a year nothing is from does not empty the list", request.tuning.relaxEmptyFilter)
    }

    @Test
    fun aGoldenSetFileIsReadWithItsFileNames() {
        val case = BenchmarkCase.fromJson(JSONObject("""
            {"id": "starter-01", "contentType": "PDF", "kind": "name",
             "queries": ["academic calendar"], "expectedFiles": ["Academic Calendar (2026-27).pdf"]}
        """))
        assertEquals(listOf("Academic Calendar (2026-27).pdf"), case.expectedFiles)
        assertEquals("name", case.kind)
        assertTrue(case.supportsRetrieval)
    }
}
