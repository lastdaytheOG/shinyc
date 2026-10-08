package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Progressive retrieval contract of [HybridSearchService]: keyword hits surface without
 * waiting for the corpus scan or the embedding model, and the FINAL stage is exactly what
 * [RetrievalService.retrieve] returns. Runs on real dispatchers (the lanes use
 * Dispatchers.IO), so it uses runBlocking + wall-clock timeouts rather than virtual time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HybridSearchProgressiveTest {

    private fun item(id: String, text: String, type: ItemType = ItemType.PHOTO) =
        VaultItem(id = id, uri = "u/$id", ocrText = text, lang = "en", itemType = type, timestamp = 0L)

    /** Mirrors SQLite: an `id IN (…)` lookup comes back in primary-key order, not the order asked. */
    private class FakeRepository(private val all: List<VaultItem>) : SearchRepository {
        var snapshotGate: CompletableDeferred<Unit>? = null
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun allItemsSnapshot(): List<VaultItem> { snapshotGate?.await(); return all }
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    private class FakeLexical(private val ranked: List<String>) : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = ranked.take(limit)
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private class FakeSemantic(
        override val isReady: Boolean = true,
        override val hasVectors: Boolean = true,
        private val hits: List<String> = emptyList(),
        private val gate: CompletableDeferred<Unit>? = null,
        private val fail: Boolean = false,
    ) : SemanticRetriever {
        @Volatile var embedCalls = 0
        override suspend fun embedQuery(query: String, trueLength: Boolean): FloatArray {
            embedCalls++
            gate?.await()
            if (fail) throw IllegalStateException("embedding model unavailable")
            return floatArrayOf(1f, 0f)
        }
        override suspend fun embedText(text: String) = floatArrayOf(1f, 0f)
        override fun searchVectors(vector: FloatArray, k: Int) = hits
    }

    private val corpus = listOf(
        item("a", "invoice from the corner shop"),
        item("b", "tax invoice number 402"),
        item("c", "random note about a holiday"),
        item("d", "invoic typo in a scanned page"),
    )

    private fun service(
        repo: SearchRepository = FakeRepository(corpus),
        bm25: List<String> = listOf("b", "a"),
        semantic: SemanticRetriever = FakeSemantic(hits = listOf("c")),
    ) = HybridSearchService(repo, FakeLexical(bm25), semantic, BoostConfig())

    private fun List<VaultItem>.ids() = map { it.id }

    @Test
    fun finalStageIsExactlyWhatRetrieveReturns() = runBlocking {
        for (query in listOf("invoice", "tax invoice number", "holiday", "zzzz")) {
            val svc = service()
            val updates = svc.retrieveProgressive(RetrievalRequest(query)).toList()
            val expected = svc.retrieve(RetrievalRequest(query)).items.ids()
            assertEquals("last emission must be FINAL for '$query'", RetrievalStage.FINAL, updates.last().stage)
            assertEquals("FINAL must equal retrieve() for '$query'", expected, updates.last().result.items.ids())
            assertEquals("exactly one FINAL for '$query'", 1, updates.count { it.stage == RetrievalStage.FINAL })
            assertEquals("stages must arrive in order for '$query'",
                updates.map { it.stage }.sorted(), updates.map { it.stage })
        }
    }

    @Test
    fun keywordHitsSurfaceBeforeTheCorpusScanAndTheModelFinish() = runBlocking {
        val scanGate = CompletableDeferred<Unit>()
        val embedGate = CompletableDeferred<Unit>()
        val repo = FakeRepository(corpus).apply { snapshotGate = scanGate }
        // The keyword engine knows "b" only; "a" says invoice too and is for the scan to find.
        val svc = service(repo = repo, bm25 = listOf("b"), semantic = FakeSemantic(hits = listOf("c"), gate = embedGate))

        val seen = Channel<RetrievalUpdate>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            svc.retrieveProgressive(RetrievalRequest("invoice")).collect { seen.send(it) }
            seen.close()
        }

        // Both the full-corpus read and the query embedding are still blocked here.
        val keyword = withTimeout(10_000) { seen.receive() }
        assertEquals(RetrievalStage.KEYWORD, keyword.stage)
        assertEquals(listOf("b"), keyword.result.items.ids())

        // Corpus scan finishes; the model is still blocked.
        scanGate.complete(Unit)
        val lexical = withTimeout(10_000) { seen.receive() }
        assertEquals(RetrievalStage.LEXICAL, lexical.stage)
        assertTrue("the scan must add the hit the keyword engine missed", "a" in lexical.result.items.ids())
        assertFalse(
            "\"invoice\" is in the vault as typed, so a near spelling of it is not a result",
            "d" in lexical.result.items.ids(),
        )

        embedGate.complete(Unit)
        val final = withTimeout(10_000) { seen.receive() }
        assertEquals(RetrievalStage.FINAL, final.stage)
        collector.join()
    }

    @Test
    fun aStageThatShowsNothingNewIsNotRepeated() = runBlocking {
        // Only "b" matches anything: the LEXICAL order equals the KEYWORD order.
        val only = listOf(item("b", "passport renewal form"))
        val svc = service(repo = FakeRepository(only), bm25 = listOf("b"), semantic = FakeSemantic(hasVectors = false))
        val stages = svc.retrieveProgressive(RetrievalRequest("passport")).toList().map { it.stage }
        assertEquals(listOf(RetrievalStage.KEYWORD, RetrievalStage.FINAL), stages)
    }

    @Test
    fun emptyVectorIndexSkipsTheQueryEmbedding() = runBlocking {
        val semantic = FakeSemantic(hasVectors = false)
        val result = service(semantic = semantic).retrieve(RetrievalRequest("invoice"))
        assertEquals("no model inference when the vector index is empty", 0, semantic.embedCalls)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun aFailingVectorLaneLeavesKeywordResults() = runBlocking {
        val withFailure = service(semantic = FakeSemantic(fail = true)).retrieve(RetrievalRequest("invoice"))
        val lexicalOnly = service(semantic = FakeSemantic(isReady = false)).retrieve(RetrievalRequest("invoice"))
        assertTrue(withFailure.items.isNotEmpty())
        assertEquals(lexicalOnly.items.ids(), withFailure.items.ids())
    }

    @Test
    fun bm25RankOrderSurvivesHydration() = runBlocking {
        // Texts deliberately do not contain the query, so only the BM25 lane ranks them and the
        // final order must be the engine's rank order — not the id order the repository returns.
        // Rows the engine names that have nothing like the query in them are no longer results,
        // so this is asked with the switch that still lists them: the order rows are hydrated
        // in does not depend on it.
        val items = listOf(item("a", "alpha"), item("m", "mike"), item("z", "zulu"))
        val svc = service(repo = FakeRepository(items), bm25 = listOf("z", "a", "m"),
            semantic = FakeSemantic(isReady = false))
        val everyEngineHit = RetrievalTuning(typoHelpOnlyForMissingWords = false)
        assertEquals(listOf("z", "a", "m"), svc.retrieve(RetrievalRequest("budget", tuning = everyEngineHit)).items.ids())
        assertTrue("and by default they are not listed at all", svc.retrieve(RetrievalRequest("budget")).items.isEmpty())
    }

    /**
     * The engine as it is just after the app is opened: it answers from what it holds so far
     * (here, nothing) until [filled] completes. [waits] is whether it says so to the service.
     */
    private class FillingLexical(
        private val filled: CompletableDeferred<Unit>,
        private val ranked: List<String>,
        private val waits: Boolean,
    ) : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = if (filled.isCompleted) ranked.take(limit) else emptyList()
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
        override suspend fun awaitReady() { if (waits) filled.await() }
    }

    @Test
    fun aSearchTypedWhileTheEngineIsBeingFilledWaitsForIt() = runBlocking {
        // Only the engine names these rows, so the list shows whether it was asked too early.
        val items = listOf(item("a", "alpha"), item("m", "mike"), item("z", "zulu"))
        val request = RetrievalRequest("budget", tuning = RetrievalTuning(typoHelpOnlyForMissingWords = false))
        fun service(filled: CompletableDeferred<Unit>, waits: Boolean) = HybridSearchService(
            FakeRepository(items), FillingLexical(filled, listOf("z", "a", "m"), waits),
            FakeSemantic(isReady = false), BoostConfig(),
        )

        val filled = CompletableDeferred<Unit>()
        val answer = async(Dispatchers.Default) { service(filled, waits = true).retrieve(request).items.ids() }
        assertEquals("no answer while the engine is being filled", null, withTimeoutOrNull(300) { answer.await() })
        filled.complete(Unit)
        assertEquals(listOf("z", "a", "m"), withTimeout(5_000) { answer.await() })

        // The control: asked without waiting, the same query lists nothing.
        val early = service(CompletableDeferred(), waits = false).retrieve(request).items.ids()
        assertEquals(emptyList<String>(), early)
    }
}
