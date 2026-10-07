package com.amar.vault.benchmark

import com.amar.vault.VaultItem
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalResult
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.RetrievalTuning
import com.amar.vault.retrieval.SearchRepository
import com.amar.vault.retrieval.SemanticRetriever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalAblationBenchmarkTest {

    private fun item(id: String) =
        VaultItem(id = id, uri = "u/$id", ocrText = id, lang = "en", itemType = "pdf", timestamp = 0L)

    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }
        override suspend fun allItemsSnapshot() = all
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    /** Ranks the right document first only when the rank-order fix is on. */
    private class TuningSensitiveRetrieval(private val right: VaultItem, private val wrong: VaultItem) : RetrievalService {
        val tunings = mutableListOf<RetrievalTuning>()
        override suspend fun retrieve(request: RetrievalRequest): RetrievalResult {
            tunings += request.tuning
            return RetrievalResult(if (request.tuning.rankOrderHydration) listOf(right, wrong) else listOf(wrong, right))
        }
    }

    private class Semantic(override val isReady: Boolean) : SemanticRetriever {
        var cacheClears = 0
        override suspend fun embedQuery(query: String, trueLength: Boolean) = FloatArray(0)
        override suspend fun embedText(text: String) = FloatArray(0)
        override fun searchVectors(vector: FloatArray, k: Int) = emptyList<String>()
        override fun clearCaches() { cacheClears++ }
    }

    private val right = item("right")
    private val wrong = item("wrong")
    private val case = BenchmarkCase(
        id = "c1", contentType = BenchmarkContentType.PDF, documentId = "right",
        queries = listOf("q1", "q2"), expectedResults = listOf("right"),
    )

    private fun BenchmarkSection.value(name: String) = metrics.single { it.name == name }.value

    @Test
    fun everyVariantIsScoredOnTheSameQueriesWithItsOwnTuning() = runBlocking {
        val retrieval = TuningSensitiveRetrieval(right, wrong)
        val semantic = Semantic(isReady = true)
        val section = RetrievalAblationBenchmark(RetrievalEvaluator(retrieval, Repo(listOf(right, wrong))), semantic)
            .run(listOf(case))

        assertEquals("retrieval.ablation", section.id)
        assertEquals(RetrievalAblationBenchmark.VARIANTS.map { it.name }, section.rows.map { it["variant"] })
        assertEquals(1.0, section.value("production.mrr")!!, 1e-9)
        assertEquals(0.5, section.value("legacyOrder.mrr")!!, 1e-9)   // right answer pushed to rank 2
        assertEquals(1.0, section.value("production.precision@1")!!, 1e-9)
        assertEquals(0.0, section.value("legacyOrder.precision@1")!!, 1e-9)
        assertEquals("-0.5000", section.rows.single { it["variant"] == "legacyOrder" }["mrrVsProduction"])
        assertEquals(2.0, section.value("queries.scored")!!, 1e-9)
        assertEquals(1.0, section.value("semantic.available")!!, 1e-9)

        // One warm-up pass with defaults, then each variant's own tuning for both queries.
        val expected = listOf(RetrievalTuning(), RetrievalTuning()) +
            RetrievalAblationBenchmark.VARIANTS.flatMap { listOf(it.tuning, it.tuning) }
        assertEquals(expected, retrieval.tunings)
        assertEquals("caches cleared before each variant", RetrievalAblationBenchmark.VARIANTS.size, semantic.cacheClears)
    }

    @Test
    fun withNoScorableCaseEveryQualityMetricIsUnmeasuredNotInvented() = runBlocking {
        val retrieval = TuningSensitiveRetrieval(right, wrong)
        // The expected document is not in the corpus, so the case cannot be scored.
        val section = RetrievalAblationBenchmark(RetrievalEvaluator(retrieval, Repo(emptyList())), Semantic(isReady = false))
            .run(listOf(case))

        for (variant in RetrievalAblationBenchmark.VARIANTS) {
            for (metric in listOf("mrr", "precision@1", "recall@10", "ndcg@10", "latency.avg")) {
                val m = section.metrics.single { it.name == "${variant.name}.$metric" }
                assertNull("${m.name} must be unmeasured", m.value)
                assertTrue("${m.name} must say why", m.note.isNotBlank())
            }
        }
        assertEquals(0.0, section.value("queries.scored")!!, 1e-9)
        assertEquals(0.0, section.value("semantic.available")!!, 1e-9)
        assertTrue(retrieval.tunings.isEmpty())
    }
}
