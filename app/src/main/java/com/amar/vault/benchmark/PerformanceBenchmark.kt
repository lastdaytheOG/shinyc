package com.amar.vault.benchmark

import com.amar.vault.QueryPlanner
import com.amar.vault.retrieval.LexicalRetriever
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.SearchRepository
import com.amar.vault.retrieval.SemanticRetriever

/**
 * Module 7 — Retrieval performance (latency).
 *
 * End-to-end latency is measured around the REAL [RetrievalService.retrieve] call —
 * the same call the search box makes — reported as avg / median / p95 / p99 over
 * `queries × ROUNDS` executions.
 *
 * Component timings are measured by invoking the same public capabilities the service
 * itself composes (query parsing via [QueryPlanner.parse], BM25 via
 * [LexicalRetriever.bm25], embed + ANN via [SemanticRetriever], hydration via
 * [SearchRepository.getByIds]) — NOT by instrumenting the pipeline. Stages that have
 * no public seam (fusion, boosting, final ranking, render prep) are reported as
 * unmeasured; they are part of end-to-end and must not be derived by subtraction
 * (that would attribute scheduler noise to a named stage).
 *
 * Queries come from the golden dataset; when the dataset has none, a fixed fallback
 * set keeps latency runs comparable device-to-device.
 */
class PerformanceBenchmark(
    private val retrieval: RetrievalService,
    private val lexical: LexicalRetriever,
    private val semantic: SemanticRetriever,
    private val repository: SearchRepository,
) {

    companion object {
        private const val ROUNDS = 3
        private val FALLBACK_QUERIES = listOf(
            "receipt", "flight ticket", "insurance policy renewal",
            "coffee march", "whatsapp screenshot travel",
        )
    }

    suspend fun run(cases: List<BenchmarkCase>): BenchmarkSection {
        val queries = cases.flatMap { it.queries }.distinct().ifEmpty { FALLBACK_QUERIES }
        val notes = if (cases.none { it.queries.isNotEmpty() })
            "dataset has no queries — using fixed fallback query set" else ""

        // ── End-to-end (the real production call) ─────────────────────────────
        val total = mutableListOf<Long>()
        // Warm-up round (untimed): JIT, caches, lazy singletons.
        for (q in queries) runCatching { retrieval.retrieve(RetrievalRequest(q)) }
        repeat(ROUNDS) {
            for (q in queries) {
                val t0 = System.nanoTime()
                retrieval.retrieve(RetrievalRequest(q))
                total.add((System.nanoTime() - t0) / 1_000_000)
            }
        }
        val sortedTotal = total.sorted()

        // ── Time to first results (what the search box paints first) ──────────
        // Same queries through the progressive form of the same call. The first emission is
        // the earliest moment the UI has a ranked list; caches are warm from the rounds above.
        val first = mutableListOf<Long>()
        repeat(ROUNDS) {
            for (q in queries) {
                val t0 = System.nanoTime()
                var firstAt: Long? = null
                retrieval.retrieveProgressive(RetrievalRequest(q)).collect {
                    if (firstAt == null) firstAt = (System.nanoTime() - t0) / 1_000_000
                }
                firstAt?.let(first::add)
            }
        }
        val sortedFirst = first.sorted()

        // ── Component seams (same public capabilities the service composes) ──
        val parse = timeEach(queries) { QueryPlanner.parse(it) }
        val bm25 = timeEach(queries) { lexical.bm25(it, 50) }
        val embed = if (semantic.isReady) timeEachSuspend(queries) { semantic.embedQuery(it) } else null
        val ann = if (semantic.isReady) timeEachSuspend(queries) { q ->
            semantic.searchVectors(semantic.embedQuery(q), 50) // embedQuery is cached ⇒ times ANN
        } else null
        val hydrate = run {
            val ids = queries.flatMap { runCatching { lexical.bm25(it, 20) }.getOrDefault(emptyList()) }.distinct().take(50)
            if (ids.isEmpty()) null else timeEachSuspend(listOf(ids)) { repository.getByIds(it) }
        }

        val semNote = if (semantic.isReady) "" else "semantic retriever not initialized"
        return BenchmarkSection(
            id = "retrieval.performance",
            title = "Retrieval latency (end-to-end + public component seams)",
            metrics = listOf(
                MetricValue("total.avg", BenchmarkMath.mean(total), "ms", false, notes),
                MetricValue("total.median", BenchmarkMath.median(sortedTotal), "ms", false),
                MetricValue("total.p95", BenchmarkMath.percentile(sortedTotal, 0.95), "ms", false),
                MetricValue("total.p99", BenchmarkMath.percentile(sortedTotal, 0.99), "ms", false),
                MetricValue("total.samples", total.size.toDouble(), "count", true),
                MetricValue("firstResults.avg", BenchmarkMath.mean(first), "ms", false,
                    "time to the first progressive emission (keyword stage when BM25 has hits)"),
                MetricValue("firstResults.median", BenchmarkMath.median(sortedFirst), "ms", false),
                MetricValue("firstResults.p95", BenchmarkMath.percentile(sortedFirst, 0.95), "ms", false),
                MetricValue("queryParsing.avg", parse, "ms", false, "QueryPlanner.parse"),
                MetricValue("bm25.avg", bm25, "ms", false, "LexicalRetriever.bm25 (native engine + cap)"),
                MetricValue("embedQuery.avg", embed, "ms", false, semNote.ifBlank { "first-call cost amortized by LRU in production" }),
                MetricValue("vectorSearch.avg", ann, "ms", false, semNote.ifBlank { "ANN over cached query vector" }),
                MetricValue("hydration.avg", hydrate, "ms", false,
                    if (hydrate == null) "no candidate ids to hydrate" else "SearchRepository.getByIds over BM25 candidates"),
                MetricValue("fusion.avg", null, "ms", false,
                    "internal to HybridSearchService — no public seam; measured only inside total"),
                MetricValue("ranking.avg", null, "ms", false,
                    "internal to HybridSearchService — no public seam; measured only inside total"),
                MetricValue("renderPrep.avg", null, "ms", false,
                    "UI-side mapping happens in SearchViewModel — outside RetrievalService; not in scope of this seam"),
            ),
        )
    }

    private inline fun <T> timeEach(inputs: List<T>, block: (T) -> Any?): Double? = try {
        inputs.forEach { block(it) } // warm-up
        val times = inputs.map { val t0 = System.nanoTime(); block(it); (System.nanoTime() - t0) / 1_000_000.0 }
        if (times.isEmpty()) null else times.average()
    } catch (e: Exception) { null }

    private suspend fun <T> timeEachSuspend(inputs: List<T>, block: suspend (T) -> Any?): Double? = try {
        inputs.forEach { block(it) } // warm-up
        val times = inputs.map { val t0 = System.nanoTime(); block(it); (System.nanoTime() - t0) / 1_000_000.0 }
        if (times.isEmpty()) null else times.average()
    } catch (e: Exception) { null }
}
