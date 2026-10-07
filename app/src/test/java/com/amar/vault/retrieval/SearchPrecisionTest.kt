package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.QueryPlanner
import com.amar.vault.SearchFilter
import com.amar.vault.SortOrder
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the list shows for what was typed — the reports made from a phone on 2026-10-07, on a
 * vault like the one they were made on: an operating-systems textbook, a syllabus, a calendar,
 * a receipt, a newsletter saved as a PDF, and two screenshots.
 *
 *  - "claude" listed the textbook and the syllabus, which say "cloud" and "clause";
 *  - "last" found nothing, and "last working days" put the calendar that says it at the bottom;
 *  - the Images and Documents chips changed nothing;
 *  - and capitals must make no difference to any of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SearchPrecisionTest {

    private val now = System.currentTimeMillis()

    private fun page(doc: String, pdfPage: Int, text: String, file: String, addedDaysAgo: Int = 0) = VaultItem(
        id = "${doc}_chunk${pdfPage - 1}", uri = "content://docs/$doc", ocrText = text, lang = "en",
        itemType = ItemType.PDF, pageNum = pdfPage, sourceFile = file,
        timestamp = now - addedDaysAgo * 24L * 60 * 60 * 1000 + pdfPage,
        parentDocumentId = doc, chunkIndex = pdfPage - 1,
    )

    private fun screenshot(id: String, text: String) = VaultItem(
        id = id, uri = "content://media/external/images/media/$id", ocrText = text, lang = "en",
        itemType = ItemType.SCREENSHOT, sourceFile = id, timestamp = now,
    )

    private val vault = listOf(
        page("newsletter", 1, "This week in Claude Code: build your own mods, a wrap-up allowance, and more", "Gmail - This week in Claude Code.pdf"),
        page("newsletter", 4, "you tried claude plugin eval from the last issue, you already know whether yours is worth shipping. higher limits for a few days", "Gmail - This week in Claude Code.pdf"),
        page("book", 7, "and my Nicolette Avi Silberschatz To Brendan and Ellen, and Barbara, Anne and Harold", "Silberschatz 9th edition.pdf", addedDaysAgo = 1),
        page("book", 300, "Figure 6.7 Multilevel feedback queues. processes in queue 0. Only when queue 0 is empty will it execute", "Silberschatz 9th edition.pdf", addedDaysAgo = 1),
        page("book", 453, "Working-set model. that working set has enough frames. On the last page fault the clause holds for cloud storage over many days", "Silberschatz 9th edition.pdf", addedDaysAgo = 1),
        page("syllabus", 30, "Expressions and Towers of Hanoi. Queues: Basic Operations, Representation using arrays. Each clause of the cloud unit", "BE_ADS_2024-26 Exam 2025-27.pdf", addedDaysAgo = 2),
        page("receipt", 1, "updated at billers end within 3 to 5 working days. this is not a GST invoice but only a confirmation receipt", "receipt_NB2609211245541479.pdf", addedDaysAgo = 3),
        page("calendar", 1, "First Mid Term Second Mid Term Last Working Day Commencement of Practical Exams. agenda of the meeting", "Academic Calendar (2026-27).pdf", addedDaysAgo = 30),
        screenshot("player", "18:09 ELLA CIAO 1 AMO the queen sings quite late. Add to queue"),
        screenshot("settings", "18:08 settings Q Search quote of the day and bread"),
    )

    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun allItemsSnapshot() = all.sortedByDescending { it.timestamp }
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    /**
     * Behaves as the native keyword engine does where it matters here: a row is a hit for a
     * word it has as a whole word; and for a word no row has whole, any row that shares
     * enough three-letter pieces with it — which is how "calendar" came up for "brenda".
     */
    private class EngineLike(private val all: List<VaultItem>) : LexicalRetriever {
        private fun words(text: String) = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

        override fun bm25(query: String, limit: Int): List<String> {
            val hits = LinkedHashSet<String>()
            for (word in words(query)) {
                val whole = all.filter { word in words("${it.ocrText} ${it.tags} ${SearchableName.of(it)}") }
                if (whole.isNotEmpty()) {
                    hits += whole.map { it.id }
                } else {
                    val pieces = word.windowed(3).toSet()
                    hits += all.filter { row ->
                        val inRow = words("${row.ocrText} ${row.tags}").flatMap { it.windowed(3) }.toSet()
                        pieces.isNotEmpty() && pieces.count { it in inRow } * 10 >= pieces.size * 4
                    }.map { it.id }
                }
            }
            return hits.take(limit)
        }

        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private object NoSemantic : SemanticRetriever {
        override val isReady = false
        override suspend fun embedQuery(query: String, trueLength: Boolean) = floatArrayOf()
        override suspend fun embedText(text: String) = floatArrayOf()
        override fun searchVectors(vector: FloatArray, k: Int) = emptyList<String>()
    }

    private val service = HybridSearchService(Repo(vault), EngineLike(vault), NoSemantic, BoostConfig())

    private fun result(typed: String, chip: String = SearchFilter.ALL) = runBlocking {
        val only: ((VaultItem) -> Boolean)? =
            if (chip == SearchFilter.ALL) null else { item -> SearchFilter.accepts(chip, item, saved = null) }
        service.retrieve(RetrievalRequest.forResultList(typed, only))
    }

    /** The documents and pictures listed for [typed], best first. */
    private fun typed(typed: String, chip: String = SearchFilter.ALL): List<String> =
        result(typed, chip).items.map { it.parentDocumentId ?: it.id }

    // ── A word that is in the vault brings no look-alikes ───────────────────────────────

    @Test
    fun claudeListsOnlyWhatSaysClaude() = assertEquals(listOf("newsletter"), typed("claude"))

    @Test
    fun brendaListsOnlyWhatSaysBrenda() = assertEquals(listOf("book"), typed("brenda"))

    @Test
    fun queueListsWhatSaysQueueAndNotWhatSaysQueenOrQuote() {
        assertEquals(setOf("book", "syllabus", "player"), typed("queue").toSet())
    }

    @Test
    fun theseAreMatchesNotLookAlikes() = assertFalse(result("claude").similarSpellingsOnly)

    // ── A word that is nowhere in the vault still gets typo help ────────────────────────

    @Test
    fun aMisspeltWordFindsTheWordItWasMeantToBe() {
        val found = result("recipt")
        assertTrue("got ${found.items.map { it.id }}", found.items.any { it.parentDocumentId == "receipt" })
        assertTrue("and the list is known to be near spellings only", found.similarSpellingsOnly)
    }

    @Test
    fun aMisspeltWordBesideARealOneKeepsItsHelp() {
        // "exams" is in the vault; "calender" is not, and is one letter from "calendar".
        assertEquals("calendar", typed("calender exams").first())
        assertFalse(result("calender exams").similarSpellingsOnly)
    }

    @Test
    fun theOldBehaviourIsStillThereToMeasureAgainst() = runBlocking {
        val before = RetrievalTuning(typoHelpOnlyForMissingWords = false)
        val listed = service.retrieve(RetrievalRequest("claude", tuning = before)).items.map { it.parentDocumentId ?: it.id }
        assertTrue("look-alikes were listed: $listed", "book" in listed && "syllabus" in listed)
    }

    @Test
    fun punctuationTypedOntoAWordDoesNotHideIt() {
        assertTrue("receipt" in typed("invoice,"))
    }

    @Test
    fun punctuationTypedOntoAWordChangesNothing() {
        // Looked for only as typed, "claude?" is a word the vault does not have, and typo help
        // for it lists what says "clause".
        assertEquals(typed("claude"), typed("claude?"))
        assertEquals(typed("queue"), typed("(queue)"))
        assertEquals(typed("brenda"), typed("brenda,"))
        assertEquals(typed("last working days"), typed("last working days?"))
        assertFalse(result("claude?").similarSpellingsOnly)
    }

    @Test
    fun aMissingWordListsNearSpellingsAndNothingElse() {
        // "brendas" is nowhere. "Brendan" is one letter from it. "agenda" shares two of its
        // three-letter pieces, which is all the keyword engine asks for — and is not a
        // spelling of it.
        val found = result("brendas")
        assertEquals(listOf("book"), found.items.map { it.parentDocumentId ?: it.id })
        assertTrue(found.similarSpellingsOnly)
    }

    @Test
    fun aMissingWordFindsTheWordItWasMadeFrom() {
        // Nothing says "executing"; the textbook says "execute", five letters from it by edits.
        val found = result("executing")
        assertEquals(listOf("book"), found.items.map { it.parentDocumentId ?: it.id })
        assertTrue(found.similarSpellingsOnly)
    }

    // ── Which page stands for a document ────────────────────────────────────────────────

    private fun rowsOf(pages: List<VaultItem>, typed: String, tuning: RetrievalTuning? = null) = runBlocking {
        HybridSearchService(Repo(pages), EngineLike(pages), NoSemantic, BoostConfig()).retrieve(
            if (tuning == null) RetrievalRequest.forResultList(typed) else RetrievalRequest(typed, tuning = tuning)
        )
    }

    /** The list as it was before: a document named by whichever page was named first. */
    private val firstPageNamed = RetrievalTuning(typoHelpOnlyForMissingWords = false, pageWordsBeforeTags = false)

    @Test
    fun aDocumentIsNamedByThePageThatSaysTheWord() {
        // The engine does not know "brenda" as a word and names pages by their three-letter
        // pieces. Page 3 has every one of them and is the page it names first; page 7 is the
        // one that says "Brendan". Named by page 3, the document was not in the list at all.
        val pages = listOf(
            page("os", 3, "a break in the current standard, to that end", "os.pdf"),
            page("os", 7, "To Brendan and Ellen", "os.pdf"),
        )
        assertEquals(listOf(7), rowsOf(pages, "brenda").items.map { it.pageNum })
        assertEquals("the engine does name page 3", listOf(3), rowsOf(pages, "brenda", firstPageNamed).items.map { it.pageNum })
    }

    @Test
    fun aDocumentFoundByANearSpellingIsNamedByThePageThatHasIt() {
        // Nothing says "recipt". Page 2 has three of its four three-letter pieces, in
        // "precipitation", which is no spelling of it; page 5 says "receipt".
        val pages = listOf(
            page("notes", 2, "annual precipitation in the hills", "notes.pdf"),
            page("notes", 5, "keep the receipt safe", "notes.pdf"),
        )
        val found = rowsOf(pages, "recipt")
        assertEquals(listOf(5), found.items.map { it.pageNum })
        assertTrue(found.similarSpellingsOnly)
        assertEquals("the engine does name page 2", listOf(2), rowsOf(pages, "recipt", firstPageNamed).items.map { it.pageNum })
    }

    // ── Words a page says, and words it is only tagged with ─────────────────────────────

    /** [row] as the indexer stores it: the page, and the tags it gave the page. */
    private fun tagged(row: VaultItem, tags: String) = row.copy(tags = tags)

    /** The list as it was before: a tag read as one more word of the page. */
    private val tagsAsText = RetrievalTuning(pageWordsBeforeTags = false)

    @Test
    fun aPageThatSaysTheWordComesBeforeOneOnlyTaggedWithIt() {
        // Both are tagged "invoice", as any page with "amount" or "bill" on it is. One says it.
        val pages = listOf(
            tagged(page("act", 1, "the assessee shall pay the amount due", "act.pdf"), "pdf document invoice billing receipt"),
            tagged(page("bill", 1, "this is not a gst invoice", "bill.pdf", addedDaysAgo = 1), "pdf document invoice billing receipt"),
        )
        fun listed(tuning: RetrievalTuning?) = rowsOf(pages, "invoice", tuning).items.map { it.parentDocumentId }
        assertEquals(listOf("bill", "act"), listed(null))
        assertEquals("before, the tag counted as much", listOf("act", "bill"), listed(tagsAsText))
    }

    @Test
    fun aDocumentIsListedByThePageThatSaysTheWordNotOneTaggedWithIt() {
        val pages = listOf(
            tagged(page("act", 2, "the assessee shall pay the amount due", "act.pdf"), "pdf document invoice billing receipt"),
            tagged(page("act", 9, "upon receipt of the invoice the officer shall", "act.pdf"), "pdf document invoice billing receipt"),
        )
        assertEquals(listOf(9), rowsOf(pages, "invoice").items.map { it.pageNum })
        assertEquals("before, it was the page named first", listOf(2), rowsOf(pages, "invoice", tagsAsText).items.map { it.pageNum })
    }

    @Test
    fun aNearSpellingOnAPageComesBeforeOneAmongTheTags() {
        // Nothing says "recipt". Page 2 is tagged "receipt"; page 5 says it.
        val pages = listOf(
            tagged(page("act", 2, "the assessee shall pay the amount due", "act.pdf"), "pdf document invoice billing receipt"),
            tagged(page("act", 5, "upon receipt of the direction", "act.pdf", addedDaysAgo = 1), "pdf document"),
        )
        assertEquals(listOf(5), rowsOf(pages, "recipt").items.map { it.pageNum })
        assertEquals("before, it was the page met first", listOf(2), rowsOf(pages, "recipt", tagsAsText).items.map { it.pageNum })
    }

    @Test
    fun aRowFoundByATagAloneIsStillListed() {
        // A payment screenshot is meant to be found by "receipt" without saying it.
        val shot = tagged(screenshot("gpay", "paid rs 250 to ravi"), "receipt payment bill invoice")
        assertEquals(listOf("gpay"), rowsOf(listOf(shot), "receipt").items.map { it.id })
        assertFalse(rowsOf(listOf(shot), "receipt").similarSpellingsOnly)
    }

    // ── The chips ───────────────────────────────────────────────────────────────────────

    @Test
    fun theImagesChipShowsPicturesOnly() = assertEquals(listOf("player"), typed("queue", SearchFilter.IMAGES))

    @Test
    fun theDocumentsChipShowsDocumentsOnly() {
        assertEquals(setOf("book", "syllabus"), typed("queue", SearchFilter.DOCUMENTS).toSet())
    }

    @Test
    fun aChipWithNothingToShowShowsNothing() = assertTrue(typed("claude", SearchFilter.IMAGES).isEmpty())

    @Test
    fun picturesAreNotCrowdedOutByALongDocument() = runBlocking {
        // More pages say "report" than the list is long; one picture says it too.
        val manual = (1..400).map { page("manual", it, "annual report section $it", "manual.pdf") }
        val picture = screenshot("whiteboard", "photo of the report on a whiteboard")
        val crowded = manual + picture
        val service = HybridSearchService(Repo(crowded), EngineLike(crowded), NoSemantic, BoostConfig())
        val images = service.retrieve(
            RetrievalRequest.forResultList("report") { SearchFilter.accepts(SearchFilter.IMAGES, it, null) }
        ).items.map { it.id }
        assertEquals(listOf("whiteboard"), images)
    }

    // ── "last", and words in the order typed ────────────────────────────────────────────

    @Test
    fun lastIsAWordToLookFor() {
        assertEquals(setOf("newsletter", "book", "calendar"), typed("last").toSet())
    }

    @Test
    fun lastWorkingDaysPutsTheCalendarThatSaysItFirst() {
        val listed = typed("last working days")
        assertEquals("got $listed", "calendar", listed.first())
        assertTrue("then the receipt that says \"working days\": $listed",
            listed.indexOf("receipt") < listed.indexOf("newsletter") && listed.indexOf("receipt") < listed.indexOf("book"))
    }

    @Test
    fun firstMidTermFindsTheCalendar() = assertEquals("calendar", typed("first mid term").first())

    @Test
    fun withoutTheBonusTheCalendarIsNotFirst() = runBlocking {
        val before = RetrievalTuning(wordOrderBonus = false)
        val listed = service.retrieve(RetrievalRequest("last working days", tuning = before)).items
            .map { it.parentDocumentId ?: it.id }
        assertFalse("the bonus is what this ranking rests on: $listed", listed.first() == "calendar")
    }

    @Test
    fun aQuestionStillReadsLastAsNewest() {
        // The search box and the ask box share a parser; only the search box treats "last" as a word.
        assertEquals(SortOrder.DESC, QueryPlanner.parse("last receipt").sortIntent)
        assertEquals("receipt", QueryPlanner.parse("last receipt").cleanedQuery)
        assertNull(QueryPlanner.parse("last receipt", forResultList = true).sortIntent)
    }

    @Test
    fun latestStillPutsTheNewestFirstInTheSearchBox() {
        val plan = QueryPlanner.parse("latest receipt", forResultList = true)
        assertEquals(SortOrder.DESC, plan.sortIntent)
        assertEquals("receipt", plan.cleanedQuery)
    }

    @Test
    fun anOrderingWordOnItsOwnIsSearchedFor() {
        val plan = QueryPlanner.parse("latest", forResultList = true)
        assertNull(plan.sortIntent)
        assertEquals("latest", plan.cleanedQuery)
    }

    // ── Capitals ────────────────────────────────────────────────────────────────────────

    @Test
    fun capitalsMakeNoDifference() {
        for (query in listOf("claude", "brenda", "queue", "last working days", "first mid term", "recipt", "gst invoice")) {
            val expected = typed(query)
            for (variant in listOf(query.uppercase(), query.replaceFirstChar { it.uppercase() },
                query.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } })) {
                assertEquals("\"$variant\" must list what \"$query\" lists", expected, typed(variant))
            }
        }
    }

    @Test
    fun capitalsMakeNoDifferenceUnderAChip() {
        assertEquals(typed("queue", SearchFilter.DOCUMENTS), typed("QUEUE", SearchFilter.DOCUMENTS))
        assertEquals(typed("queue", SearchFilter.IMAGES), typed("Queue", SearchFilter.IMAGES))
    }
}
