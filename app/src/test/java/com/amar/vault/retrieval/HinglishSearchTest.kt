package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HinglishSearchTest {

    private fun row(id: String, text: String, lang: String, file: String = "$id.pdf") = VaultItem(
        id = id, uri = "content://docs/$id", ocrText = text, lang = lang, itemType = ItemType.PDF,
        sourceFile = file, timestamp = 0L,
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

    private class Engine(private val ranked: List<String>) : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = ranked.take(limit)
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private val english = (1..12).map { row("en$it", "Monthly phone bill number $it, amount due at the end of the month", "en") }
    private val water = row("water", "नगर निगम जल कार्य विभाग\nपानी का बिल\nदेय राशि ₹612.00", "hi", "jal_101.pdf")
    private val power = row("power", "विद्युत वितरण निगम\nबिजली बिल\nकुल देय राशि", "hi", "download1.pdf")
    private val notes = row("notes", "यह नोट्स का पन्ना है", "hi", "notes.pdf")
    private val chat = row("chat", "कार्ड छप गए हैं\nमेहंदी की रस्म शुक्रवार को", "hi", "Screenshot_1.png")
    private val vault = english + listOf(water, power, notes, chat)

    private fun search(query: String, tuning: RetrievalTuning = RetrievalTuning(), guesser: SpellingGuesser? = null) = runBlocking {
        val service = HybridSearchService(Repo(vault), Engine(english.map { it.id } + listOf("power", "water", "notes", "chat")),
            BoostConfig(), spellings = guesser)
        service.retrieve(RetrievalRequest(query, tuning = tuning))
    }

    @Test
    fun aHindiBillThatSaysTheTypedWordsComesFirst() {
        val result = search("paani ka bill")
        assertEquals("water", result.items.first().id)
        assertFalse(result.similarSpellingsOnly)
        assertEquals(EvidenceKind.SAYS, result.groups.first().kind)
    }

    @Test
    fun withoutCountingHindiWordsAsSaidItWasLowDown() {
        val result = search("paani ka bill", RetrievalTuning(hinglishSaid = false))
        assertNotEquals("water", result.items.first().id)
    }

    @Test
    fun aRowThatOnlySaysALittleWordInHindiGetsNothingForIt() {
        val result = search("paani ka bill")
        val notesAt = result.items.indexOfFirst { it.id == "notes" }
        val waterAt = result.items.indexOfFirst { it.id == "water" }
        assertTrue(notesAt == -1 || notesAt > waterAt)
    }

    @Test
    fun aWordFoundOnlyInHindiIsSaidNotNearlySpelt() {
        val result = search("bijli")
        assertEquals("power", result.items.first().id)
        assertFalse(result.similarSpellingsOnly)
        assertEquals(1, result.keywordWordsSaid)
    }

    @Test
    fun theGuesserFindsASpellingTheRulesMiss() {
        val guesser = SpellingGuesser { w -> if (w == "mehndi") listOf("mehandi") else emptyList() }
        val withModel = search("mehndi", guesser = guesser)
        assertEquals("chat", withModel.items.first().id)
        assertEquals(EvidenceKind.SAYS, withModel.groups.first().kind)
        val withRules = search("mehndi", RetrievalTuning(hinglishModel = false), guesser)
        assertTrue(withRules.items.none { it.id == "chat" } || withRules.similarSpellingsOnly)
    }

    @Test
    fun firstResultsDoNotWaitForTheGuesses() = runBlocking {
        val slow = SpellingGuesser { Thread.sleep(1500); emptyList() }
        val service = HybridSearchService(Repo(vault), Engine(english.map { it.id } + listOf("power", "water", "notes", "chat")),
            BoostConfig(), spellings = slow)
        val start = System.nanoTime()
        val seen = ArrayList<Pair<RetrievalStage, Double>>()
        service.retrieveProgressive(RetrievalRequest("paani ka bill")).collect { seen += it.stage to (System.nanoTime() - start) / 1e6 }
        assertEquals(RetrievalStage.KEYWORD, seen.first().first)
        assertTrue("first stage at ${seen.first().second} ms", seen.first().second < 1000)
        assertTrue(seen.last().first == RetrievalStage.FINAL && seen.last().second >= 1500)
    }

    @Test
    fun aQueryOfEnglishWordsIsNotReadWithGuesses() = runBlocking {
        val code = row("code", "Your verification code is 4821", "en")
        val hindi = row("chatcode", "कोड बता दीजिए", "hi", "Screenshot_2.png")
        val guesser = SpellingGuesser { w -> if (w == "code") listOf("kod") else emptyList() }
        val service = HybridSearchService(Repo(listOf(code, hindi)), Engine(listOf("chatcode", "code")), BoostConfig(), spellings = guesser)
        val english = service.retrieve(RetrievalRequest("verification code"))
        assertEquals("code", english.items.first().id)
        assertTrue(english.hindiSpellings.isEmpty())
        val mixed = service.retrieve(RetrievalRequest("code batao"))
        assertEquals(listOf("कोड"), mixed.hindiSpellings)
    }

    @Test
    fun englishQueriesAreLeftAsTheyWere() {
        val result = search("phone bill")
        assertTrue(result.items.first().id.startsWith("en"))
    }

    @Test
    fun hindiMatchesReadTheWholeQueryInOrder() {
        val q = Hinglish.Query.of(listOf("paani", "ka", "bill"), null)
        val m = q.match(water.ocrText, "jal_101.pdf")!!
        assertTrue(m.said.all { it })
        assertEquals(3, m.run)
        assertTrue(m.onPage)
        assertEquals(listOf("पानी", "बिल"), m.spellings)
        assertNull(q.match(notes.ocrText, "notes.pdf"))
        assertNull(q.match("Monthly phone bill", "bill.pdf"))
    }

    @Test
    fun aGuessedSpellingCountsLikeARuleSpelling() {
        val words = listOf("pehchan", "patra")
        val text = "मतदाता फोटो पहचान पत्र"
        assertNull(Hinglish.Query.of(words, null).match(text, ""))
        val guesser = SpellingGuesser { w -> mapOf("pehchan" to listOf("pahchan"), "patra" to listOf("patr"))[w].orEmpty() }
        val m = Hinglish.Query.of(words, guesser).match(text, "")!!
        assertTrue(m.said.all { it })
        assertEquals(2, m.run)
    }

    @Test
    fun aHindiNameCountsButIsNotThePage() {
        val m = Hinglish.Query.of(listOf("himalay"), null).match("Notes from the trip", "हिमालय notes.pdf")!!
        assertTrue(m.said[0])
        assertFalse(m.onPage)
    }
}
