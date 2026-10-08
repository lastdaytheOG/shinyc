package com.amar.vault.retrieval

import com.amar.vault.AcronymDictionary
import com.amar.vault.ItemType
import com.amar.vault.Understood
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
 * What the search box lists when part of the query was read as more than words: a reading
 * never leaves the list silently empty, and what an abbreviation stands for counts only where
 * a page says it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class QueryReadingSearchTest {

    private val now = System.currentTimeMillis()
    private val longAgo = now - 400L * 24 * 60 * 60 * 1000

    private fun pdf(doc: String, text: String, timestamp: Long = longAgo, file: String = "$doc.pdf") = VaultItem(
        id = "${doc}_chunk0", uri = "content://docs/$doc", ocrText = text, lang = "en",
        itemType = ItemType.PDF, pageNum = 1, sourceFile = file, timestamp = timestamp,
        parentDocumentId = doc, chunkIndex = 0,
    )

    /** Everything here was added more than a year ago: nothing is from last month or today. */
    private val vault = listOf(
        pdf("minutes", "the report for last month was read and approved by the committee"),
        pdf("bill", "electricity charges invoice for the quarter"),
        pdf("bank", "your OTP is 4471, do not share it with anyone"),
        pdf("policy", "a one-time password is sent to the registered mobile number"),
        pdf("manual", "enter the One Time\nPassword shown on the screen"),
        pdf("essay", "at one point in time the author says nothing of note"),
        pdf("notes", "the application was filed; the programming of the interface took a year"),
        pdf("guide", "the Application Programming Interface is documented in chapter two"),
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

    private fun search(request: RetrievalRequest) = runBlocking { service.retrieve(request) }
    private fun RetrievalResult.documents() = items.map { it.parentDocumentId ?: it.id }.toSet()

    // ── A reading never leaves the list silently empty ──────────────────────────────────

    @Test
    fun aPeriodTypedOnItsOwnThatHoldsNothingIsSearchedAsWords() {
        val result = search(RetrievalRequest.forResultList("last month"))

        assertEquals("the page that says \"last month\"", setOf("minutes"), result.documents())
        assertTrue("…and the caller is told the period was dropped", result.filterDropped)
    }

    @Test
    fun beforeAPeriodTypedOnItsOwnLeftAnEmptyList() {
        // The control: the request as it was, with nothing to look for once the period is gone.
        val asItWas = RetrievalRequest.forResultList("last month").copy(wordsIfFilterEmpty = null)
        assertTrue(search(asItWas).items.isEmpty())
    }

    @Test
    fun aPeriodThatHoldsNothingIsDroppedFromALongerQueryToo() {
        val result = search(RetrievalRequest.forResultList("invoice last month"))

        assertTrue("the invoice is listed: ${result.documents()}", "bill" in result.documents())
        assertTrue(result.filterDropped)
    }

    @Test
    fun aPeriodThatHoldsSomethingIsKeptAndNothingIsSaid() {
        val withToday = listOf(pdf("fresh", "invoice raised this morning", timestamp = now)) + vault
        val service = HybridSearchService(Repo(withToday), NoKeywordIndex, NoSemantic, BoostConfig())
        val result = runBlocking { service.retrieve(RetrievalRequest.forResultList("invoice today")) }

        assertEquals(setOf("fresh"), result.documents())
        assertFalse(result.filterDropped)
    }

    @Test
    fun aQueryWithNoReadingIsNeverMarkedAsDropped() {
        assertFalse(search(RetrievalRequest.forResultList("electricity")).filterDropped)
        assertFalse(search(RetrievalRequest.forResultList("zzzzqqqq")).filterDropped)
    }

    @Test
    fun aPeriodTakenBackIsSearchedAsWordsFromTheStart() {
        val reading = RetrievalRequest.forResultList("last month").plan!!.understood.single()
        val result = search(RetrievalRequest.forResultList("last month", asWords = setOf(reading.key)))

        assertEquals(setOf("minutes"), result.documents())
        assertFalse("nothing was dropped: nothing was read", result.filterDropped)
    }

    // ── What an abbreviation stands for ─────────────────────────────────────────────────

    @Test
    fun anAbbreviationFindsThePagesThatSayItOrSayWhatItStandsFor() {
        val found = search(RetrievalRequest.forResultList("otp")).documents()

        assertTrue("says OTP: $found", "bank" in found)
        assertTrue("says one-time password: $found", "policy" in found)
        assertTrue("says it across a line break: $found", "manual" in found)
        assertFalse("says \"one\" and \"time\", and nothing about a password: $found", "essay" in found)
    }

    @Test
    fun takenAWordAtATimeTheMeaningListedPagesThatDoNotSayIt() {
        // The control: how the meaning was looked for before — each of its words on its own.
        val essay = "at one point in time the author says nothing of note"
        val oneAtATime = listOf("one", "time", "password").map(::QueryWord)
        assertTrue(oneAtATime.any { it.isIn(essay) })
        assertFalse(QueryWord.phrase("one time password").isIn(essay))
        assertTrue(QueryWord.phrase("one time password").isIn("a one-time password is sent"))
        assertTrue(QueryWord.phrase("one time password").isIn("enter the one time\npassword shown"))
        assertFalse("the words in another order", QueryWord.phrase("one time password").isIn("password one time"))
        assertFalse("inside another word", QueryWord.phrase("one time password").isIn("someone time password"))
    }

    @Test
    fun aLongerMeaningIsHeldToTheSameRule() {
        val found = search(RetrievalRequest.forResultList("api")).documents()

        assertEquals(setOf("guide"), found)
    }

    @Test
    fun withItsMeaningTakenBackAnAbbreviationIsOnlyItself() {
        val reading = AcronymDictionary.understoodIn("otp").single()
        assertEquals(Understood.Kind.MEANING, reading.kind)

        val found = search(RetrievalRequest.forResultList("otp", asWords = setOf(reading.key))).documents()

        assertEquals(setOf("bank"), found)
    }

    @Test
    fun aPhraseOnThePageIsOnThePageNotAmongItsTags() {
        val meaning = QueryWord.phrase("one time password")
        val page = "enter the one time password"
        val withTags = "$page\npdf document"
        assertTrue(meaning.isIn(withTags, end = page.length))
        assertFalse(meaning.isIn("pdf document\none time password", end = "pdf document".length))
    }
}
