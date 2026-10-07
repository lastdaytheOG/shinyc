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

    private class Semantic(private val hits: List<SemanticHit> = emptyList()) : SemanticRetriever {
        override val isReady = true
        val trueLengthFlags = mutableListOf<Boolean>()
        override suspend fun embedQuery(query: String, trueLength: Boolean): FloatArray {
            trueLengthFlags += trueLength
            return floatArrayOf(1f, 0f)
        }
        override suspend fun embedText(text: String) = floatArrayOf(1f, 0f)
        override fun searchVectors(vector: FloatArray, k: Int) = hits.map { it.chunkId }
        override fun searchHits(vector: FloatArray, k: Int) = hits
    }

    private fun List<VaultItem>.ids() = map { it.id }

    // ── semanticParentHits ──────────────────────────────────────────────────────────────

    /** A photo of an electricity bill whose OCR text never says "light bill". */
    private val corpus = listOf(
        item("photo", "monthly electricity charges consumer number 4471"),
        item("note", "light bill reminder for the flat"),
        item("other", "holiday itinerary and hotel booking"),
    )

    private fun semanticService(photoSimilarity: Float) = HybridSearchService(
        Repo(corpus), Lexical(bm25 = listOf("note")),
        Semantic(listOf(
            // The image's vector is keyed by its chunk id; only parentId names a real row.
            SemanticHit("photo_chunk0", "photo", photoSimilarity),
            SemanticHit("other_chunk0", "other", 0.20f),
        )),
        BoostConfig(),
    )

    @Test
    fun byDefaultAnItemWithNoQueryWordIsNeverFound() = runBlocking {
        val result = semanticService(0.80f).retrieve(RetrievalRequest("light bill"))
        assertEquals(listOf("note"), result.items.ids())
    }

    @Test
    fun withParentHitsAnItemIsFoundByMeaningAlone() = runBlocking {
        val tuning = RetrievalTuning(semanticParentHits = true, semanticMinSimilarity = 0.55f)
        val result = semanticService(0.80f).retrieve(RetrievalRequest("light bill", tuning = tuning))
        assertTrue("the photo must be reachable through its parent id", "photo" in result.items.ids())
        assertFalse("a hit under the floor must not get in", "other" in result.items.ids())
        assertEquals("the keyword match still ranks first", "note", result.items.first().id)
    }

    @Test
    fun aHitBelowTheFloorStaysOut() = runBlocking {
        val tuning = RetrievalTuning(semanticParentHits = true, semanticMinSimilarity = 0.55f)
        val result = semanticService(0.54f).retrieve(RetrievalRequest("light bill", tuning = tuning))
        assertEquals(listOf("note"), result.items.ids())
    }

    // ── semanticEnabled ─────────────────────────────────────────────────────────────────

    @Test
    fun keywordOnlyNeverTouchesTheModel() = runBlocking {
        // A phrase query with a document among the keyword hits: the one case that also runs
        // the late passage boosts when semantic is enabled.
        val docs = listOf(item("d1", "refund policy for all returned items in seven days", type = ItemType.PDF))
        val semantic = Semantic()
        val svc = HybridSearchService(Repo(docs), Lexical(listOf("d1")), semantic, BoostConfig())

        svc.retrieve(RetrievalRequest("refund policy", tuning = RetrievalTuning(semanticEnabled = false)))
        assertTrue("no model call when semantic is off", semantic.trueLengthFlags.isEmpty())

        val withSemantic = svc.retrieve(RetrievalRequest("refund policy"))
        assertTrue("the default still embeds", semantic.trueLengthFlags.isNotEmpty())
        assertEquals(listOf("d1"), withSemantic.items.ids())
    }

    // ── queryTrueLength ─────────────────────────────────────────────────────────────────

    @Test
    fun queryTrueLengthIsPassedThroughToTheEmbedder() = runBlocking {
        val semantic = Semantic()
        val svc = HybridSearchService(Repo(corpus), Lexical(listOf("note")), semantic, BoostConfig())
        svc.retrieve(RetrievalRequest("light bill", tuning = RetrievalTuning(queryTrueLength = false)))
        svc.retrieve(RetrievalRequest("light bill", tuning = RetrievalTuning(queryTrueLength = true)))
        assertEquals(listOf(false, true), semantic.trueLengthFlags.distinct())
    }

    // ── candidateBounded ────────────────────────────────────────────────────────────────

    @Test
    fun boundedModeDoesNotReadTheWholeCorpusWhenTheIndexesHaveCandidates() = runBlocking {
        val repo = Repo(corpus)
        val svc = HybridSearchService(repo, Lexical(bm25 = listOf("note"), fts = listOf("note")), Semantic(), BoostConfig())

        val bounded = svc.retrieve(RetrievalRequest("light bill", tuning = RetrievalTuning(candidateBounded = true)))
        assertEquals("bounded mode must not scan the corpus", 0, repo.snapshotLoads)
        assertEquals(listOf("note"), bounded.items.ids())

        svc.retrieve(RetrievalRequest("light bill"))
        assertEquals("the default still scans it once", 1, repo.snapshotLoads)
    }

    @Test
    fun boundedModeFallsBackToTheFullScanWhenTheIndexesRecallNothing() = runBlocking {
        val repo = Repo(corpus)
        val svc = HybridSearchService(repo, Lexical(bm25 = emptyList()), Semantic(), BoostConfig())
        // A typo the keyword indexes cannot match: only the full-scan fuzzy lane can rescue it.
        val result = svc.retrieve(RetrievalRequest("remindr", tuning = RetrievalTuning(candidateBounded = true)))
        assertEquals(1, repo.snapshotLoads)
        assertEquals(listOf("note"), result.items.ids())
    }

    // ── rankOrderHydration ──────────────────────────────────────────────────────────────

    @Test
    fun rankOrderHydrationOffReproducesTheLegacyIdOrder() = runBlocking {
        val items = listOf(item("a", "alpha"), item("m", "mike"), item("z", "zulu"))
        val svc = HybridSearchService(Repo(items), Lexical(listOf("z", "a", "m")), Semantic(), BoostConfig())
        // No text has the query in it, so that the engine's order is the only order there is.
        // Such rows are results only with typo help for every word, which is asked for here.
        val everyEngineHit = RetrievalTuning(typoHelpOnlyForMissingWords = false)
        val legacy = svc.retrieve(RetrievalRequest("budget", tuning = everyEngineHit.copy(rankOrderHydration = false)))
        val fixed = svc.retrieve(RetrievalRequest("budget", tuning = everyEngineHit.copy(rankOrderHydration = true)))
        assertEquals(listOf("a", "m", "z"), legacy.items.ids())
        assertEquals(listOf("z", "a", "m"), fixed.items.ids())
    }
}
