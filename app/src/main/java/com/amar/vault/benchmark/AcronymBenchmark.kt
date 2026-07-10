package com.amar.vault.benchmark

import com.amar.vault.AcronymDictionary
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.SearchRepository

/**
 * Sprint 4B — acronym equivalence benchmark (extends the Sprint 3C framework).
 *
 * Verifies the core claim of the acronym-expansion layer: a bare acronym query retrieves
 * the same documents as its expanded form —
 *
 *   AI  == Artificial Intelligence
 *   ML  == Machine Learning
 *   OCR == Optical Character Recognition
 *   PDF == Portable Document Format
 *   LLM == Large Language Model
 *
 * Method (measured only — nothing fabricated):
 *  - both the acronym query and its full-form query are run through the REAL production
 *    [RetrievalService] (never a re-implementation);
 *  - result chunk ids are collapsed to parent documents (Sprint 3B ownership);
 *  - the full-form result set is treated as the reference for that concept, and we measure
 *      recall    = |acronym ∩ fullform| / |fullform|   (did the acronym recover them?)
 *      precision = |acronym ∩ fullform| / |acronym|     (are acronym hits legitimate?)
 *  - latency is the acronym query's wall time.
 *
 * Honesty: a pair whose full-form query returns nothing on this corpus is UNMEASURABLE
 * (no reference set) and is counted in `pairs.skipped` with a per-row reason — it is never
 * scored as a trivial 1.0. If no pair is measurable, recall/precision are reported null.
 */
class AcronymBenchmark(
    private val retrieval: RetrievalService,
    private val repository: SearchRepository,
) {

    /** The acceptance set. Full forms are sourced from the dictionary to stay in sync. */
    private val acronyms = listOf("AI", "ML", "OCR", "PDF", "LLM", "NLP", "RAG", "JSON", "SQL", "HTTP")

    private companion object {
        const val TOP_K = 10
    }

    suspend fun run(): BenchmarkSection {
        val rows = mutableListOf<Map<String, String>>()
        val recalls = mutableListOf<Double>()
        val precisions = mutableListOf<Double>()
        val latencies = mutableListOf<Long>()
        var skipped = 0

        for (acronym in acronyms) {
            val fullForm = AcronymDictionary.expansionOf(acronym) ?: continue

            val startAcr = System.nanoTime()
            val acrDocs = topDocs(acronym)
            val acrLatencyMs = (System.nanoTime() - startAcr) / 1_000_000

            val refDocs = topDocs(fullForm)

            // No reference documents on this corpus → cannot measure equivalence honestly.
            if (refDocs.isEmpty() && acrDocs.isEmpty()) {
                skipped++
                rows.add(mapOf(
                    "acronym" to acronym, "fullForm" to fullForm, "status" to "SKIPPED",
                    "reason" to "no documents match either form on this corpus",
                ))
                continue
            }
            if (refDocs.isEmpty()) {
                skipped++
                rows.add(mapOf(
                    "acronym" to acronym, "fullForm" to fullForm, "status" to "SKIPPED",
                    "reason" to "full-form query returned no reference documents",
                ))
                continue
            }

            val overlap = acrDocs.intersect(refDocs).size
            val recall = overlap.toDouble() / refDocs.size
            val precision = if (acrDocs.isEmpty()) 0.0 else overlap.toDouble() / acrDocs.size
            recalls.add(recall)
            precisions.add(precision)
            latencies.add(acrLatencyMs)

            rows.add(mapOf(
                "acronym" to acronym, "fullForm" to fullForm, "status" to "SCORED",
                "acronymHits" to acrDocs.size.toString(),
                "fullFormHits" to refDocs.size.toString(),
                "overlap" to overlap.toString(),
                "recall" to fmt(recall), "precision" to fmt(precision),
                "latencyMs" to acrLatencyMs.toString(),
            ))
        }

        val none = recalls.isEmpty()
        val note = if (none) "no acronym pair had reference documents on this corpus" else ""
        val sortedLat = latencies.sorted()

        return BenchmarkSection(
            id = "retrieval.acronym",
            title = "Acronym equivalence (acronym query vs expanded form, real RetrievalService)",
            metrics = listOf(
                MetricValue("acronym.recall.mean",
                    if (none) null else recalls.average(), "ratio", true, note),
                MetricValue("acronym.precision.mean",
                    if (none) null else precisions.average(), "ratio", true, note),
                MetricValue("acronym.latency.median.ms",
                    BenchmarkMath.median(sortedLat), "ms", false, note),
                MetricValue("acronym.latency.p95.ms",
                    BenchmarkMath.percentile(sortedLat, 0.95), "ms", false, note),
                MetricValue("pairs.scored", recalls.size.toDouble(), "count", true),
                MetricValue("pairs.skipped", skipped.toDouble(), "count", false,
                    if (skipped > 0) "no reference documents on this corpus" else ""),
            ),
            rows = rows,
        )
    }

    /** Top-K parent-document ids for [query] via the real retrieval pipeline. */
    private suspend fun topDocs(query: String): Set<String> {
        val result = retrieval.retrieve(RetrievalRequest(query))
        return result.items.asSequence()
            .map { it.parentDocumentId ?: it.id }
            .distinct()
            .take(TOP_K)
            .toSet()
    }

    private fun fmt(v: Double) = "%.4f".format(java.util.Locale.US, v)
}
