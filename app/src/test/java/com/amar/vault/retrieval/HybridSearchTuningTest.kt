package com.amar.vault.retrieval

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

/** Each [RetrievalTuning] switch does what it says, and the defaults leave behaviour alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HybridSearchTuningTest {

    private fun item(id: String, text: String, type: ItemType = ItemType.PHOTO) =
        VaultItem(id = id, uri = "u/$id", ocrText = text, lang = "en", itemType = type, timestamp = 0L)

    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        var snapshotLoads = 0
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun allItemsSnapshot(): List<VaultItem> { snapshotLoads++; return all }
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    private class Lexical(private val bm25: List<String>, private val fts: List<String> = emptyList()) : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = bm25.take(limit)
        override suspend fun fts(query: String, limit: Int) = fts.take(limit)
    }

    private fun List<VaultItem>.ids() = map { it.id }

    private val corpus = listOf(
        item("photo", "monthly electricity charges consumer number 4471"),
        item("note", "light bill reminder for the flat"),
        item("other", "holiday itinerary and hotel booking"),
    )

    // ── candidateBounded ────────────────────────────────────────────────────────────────

    @Test
    fun boundedModeDoesNotReadTheWholeCorpusWhenTheIndexesHaveCandidates() = runBlocking {
        val repo = Repo(corpus)
        val svc = HybridSearchService(repo, Lexical(bm25 = listOf("note"), fts = listOf("note")), BoostConfig())

        val bounded = svc.retrieve(RetrievalRequest("light bill", tuning = RetrievalTuning(candidateBounded = true)))
        assertEquals("bounded mode must not scan the corpus", 0, repo.snapshotLoads)
        assertEquals(listOf("note"), bounded.items.ids())

        svc.retrieve(RetrievalRequest("light bill"))
        assertEquals("the default is bounded too", 0, repo.snapshotLoads)
        svc.retrieve(RetrievalRequest("light bill", tuning = RetrievalTuning(candidateBounded = false)))
        assertEquals("the full scan is still there to measure against", 1, repo.snapshotLoads)
    }

    @Test
    fun boundedModeFallsBackToTheFullScanWhenTheIndexesRecallNothing() = runBlocking {
        val repo = Repo(corpus)
        val svc = HybridSearchService(repo, Lexical(bm25 = emptyList()), BoostConfig())
        // A typo the keyword indexes cannot match: only the full-scan fuzzy lane can rescue it.
        val result = svc.retrieve(RetrievalRequest("remindr", tuning = RetrievalTuning(candidateBounded = true)))
        assertEquals(1, repo.snapshotLoads)
        assertEquals(listOf("note"), result.items.ids())
    }

    // ── rankOrderHydration ──────────────────────────────────────────────────────────────

    @Test
    fun rankOrderHydrationOffReproducesTheLegacyIdOrder() = runBlocking {
        val items = listOf(item("a", "alpha"), item("m", "mike"), item("z", "zulu"))
        val svc = HybridSearchService(Repo(items), Lexical(listOf("z", "a", "m")), BoostConfig())
        // No text has the query in it, so that the engine's order is the only order there is.
        // Such rows are results only with typo help for every word, which is asked for here.
        val everyEngineHit = RetrievalTuning(typoHelpOnlyForMissingWords = false)
        val legacy = svc.retrieve(RetrievalRequest("budget", tuning = everyEngineHit.copy(rankOrderHydration = false)))
        val fixed = svc.retrieve(RetrievalRequest("budget", tuning = everyEngineHit.copy(rankOrderHydration = true)))
        assertEquals(listOf("a", "m", "z"), legacy.items.ids())
        assertEquals(listOf("z", "a", "m"), fixed.items.ids())
    }
}
