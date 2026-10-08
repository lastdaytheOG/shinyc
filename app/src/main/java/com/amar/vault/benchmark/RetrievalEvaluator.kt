package com.amar.vault.benchmark

import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.RetrievalTuning
import com.amar.vault.retrieval.SearchRepository
import kotlin.math.ln

/**
 * Module 3 — Retrieval quality evaluation.
 *
 * Runs each golden case's queries through the REAL production [RetrievalService]
 * (HybridSearchService → final ranking) — never a re-implementation — and scores the
 * final ranked list against the case's expectations.
 *
 * Metrics: Precision@1/5/10, Recall@5/10, MRR, NDCG@10 — averaged over all scored
 * queries. Result ids are resolved to their parent document (via the explicit
 * `parentDocumentId` from Sprint 3B) so chunk-level hits score against document-level
 * expectations.
 *
 * Honesty rules:
 *  - a case whose expected documents are not present in the corpus is SKIPPED and
 *    counted in `cases.skipped` (with a per-case row explaining why);
 *  - if zero cases are scorable, all quality metrics are reported as unmeasured.
 */
class RetrievalEvaluator(
    private val retrieval: RetrievalService,
    private val repository: SearchRepository,
) {

    data class QueryScore(
        val caseId: String,
        val query: String,
        val p1: Double, val p5: Double, val p10: Double,
        val r5: Double, val r10: Double,
        val mrr: Double, val ndcg10: Double,
        /** Where the first right answer stands in the list, from 1; 0 when none is in it. */
        val rank: Int = 0,
        /** What the case is there to test ([BenchmarkCase.kind]). */
        val kind: String = "",
        /** What the first result is called, right or wrong. */
        val top: String = "",
        /**
         * Of the first N results, N being how many right answers there are, the share that are
         * right. 1.0 means nothing wrong stands above or among the right ones.
         */
        val rightOnTop: Double = 0.0,
    )

    /** Every scorable query's score under one tuning, and how long each retrieval took. */
    class Scored(
        val scores: List<QueryScore>,
        val rows: List<Map<String, String>>,
        val skipped: Int,
        val latenciesMs: List<Long>,
    )

    /**
     * Score every scorable golden query under [tuning] (the production defaults unless a
     * benchmark asks otherwise — see [RetrievalAblationBenchmark]).
     */
    suspend fun scoreAll(cases: List<BenchmarkCase>, tuning: RetrievalTuning = RetrievalTuning()): Scored {
        val retrievalCases = cases.filter { it.supportsRetrieval }
        val scores = mutableListOf<QueryScore>()
        val rows = mutableListOf<Map<String, String>>()
        val latencies = mutableListOf<Long>()
        var skipped = 0

        // What each thing in the vault is called → its id here, for cases that name files.
        val byName = HashMap<String, String>()
        val names = HashMap<String, String>()
        // Every id a result can collapse to: an item's own, or the document it is a page of.
        val inVault = HashSet<String>()
        if (retrievalCases.isNotEmpty()) {
            for (item in repository.allItemsSnapshot()) {
                val id = item.parentDocumentId ?: item.id
                inVault += id
                val name = (if (item.parentDocumentId != null) item.sourceFile else item.title ?: item.sourceFile)
                if (name.isNotBlank()) { byName.putIfAbsent(name, id); names.putIfAbsent(id, name) }
            }
        }

        for (rawCase in retrievalCases) {
            val named = rawCase.expectedFiles.map { it to byName[it] }
            val notHere = named.filter { it.second == null }.map { it.first }
            if (notHere.isNotEmpty()) {
                skipped++
                rows.add(mapOf(
                    "caseId" to rawCase.id, "status" to "SKIPPED",
                    "reason" to "expected file(s) not in this vault: ${notHere.joinToString()}"
                ))
                continue
            }
            // A case that names files is scored exactly as one that names ids.
            val case = if (named.isEmpty()) rawCase else named.mapNotNull { it.second }.let { ids ->
                rawCase.copy(documentId = ids.first(), expectedResults = ids, expectedRank = ids)
            }
            val relevant = (case.expectedResults + case.documentId).filter { it.isNotBlank() }.toSet()
            // Scorable only if every expected document actually exists in the corpus —
            // otherwise the case measures the dataset, not retrieval.
            // A document picked from the phone's files has pages and no row of its own, so
            // looking its id up as a row found nothing: every golden query about such a
            // document was skipped as "not indexed" and never scored.
            val missing = relevant - inVault
            if (missing.isNotEmpty()) {
                skipped++
                rows.add(mapOf(
                    "caseId" to case.id, "status" to "SKIPPED",
                    "reason" to "expected document(s) not indexed: ${missing.joinToString()}"
                ))
                continue
            }

            for (query in case.queries) {
                val t0 = System.nanoTime()
                // As the search box sends it: the typed text is read first (a date, an order),
                // and a date that matches nothing is dropped. Scored as a bare query, the
                // numbers were for a search the app never runs.
                val asTyped = RetrievalRequest.forResultList(query)
                val result = retrieval.retrieve(asTyped.copy(tuning = tuning.copy(relaxEmptyFilter = true)))
                latencies.add((System.nanoTime() - t0) / 1_000_000)
                // Collapse chunk hits to unique parent documents, preserving rank order.
                val ranked = result.items.map { it.parentDocumentId ?: it.id }.distinct()
                val score = score(case, query, ranked, relevant).copy(
                    rank = ranked.indexOfFirst { it in relevant } + 1,
                    kind = case.kind,
                    top = ranked.firstOrNull()?.let { names[it] ?: it }.orEmpty(),
                    rightOnTop = ranked.take(relevant.size).count { it in relevant }.toDouble() / relevant.size,
                )
                scores.add(score)
                rows.add(mapOf(
                    "caseId" to case.id, "status" to "SCORED", "query" to query,
                    "p1" to fmt(score.p1), "p5" to fmt(score.p5), "p10" to fmt(score.p10),
                    "r5" to fmt(score.r5), "r10" to fmt(score.r10),
                    "mrr" to fmt(score.mrr), "ndcg10" to fmt(score.ndcg10),
                    "rank" to score.rank.toString(), "kind" to score.kind, "top" to score.top,
                ))
            }
        }

        return Scored(scores, rows, skipped, latencies)
    }

    suspend fun evaluate(cases: List<BenchmarkCase>): BenchmarkSection {
        val scored = scoreAll(cases)
        val scores = scored.scores
        val rows = scored.rows
        val skipped = scored.skipped

        val none = scores.isEmpty()
        val noneNote = if (none) "no scorable cases (dataset empty or documents not indexed)" else ""
        fun agg(name: String, pick: (QueryScore) -> Double) = MetricValue(
            name = name,
            value = if (none) null else scores.map(pick).average(),
            unit = "ratio", higherIsBetter = true, note = noneNote,
        )

        return BenchmarkSection(
            id = "retrieval.quality",
            title = "Retrieval quality (RetrievalService → HybridSearchService → final ranking)",
            metrics = listOf(
                agg("precision@1") { it.p1 },
                agg("precision@5") { it.p5 },
                agg("precision@10") { it.p10 },
                agg("recall@5") { it.r5 },
                agg("recall@10") { it.r10 },
                agg("mrr") { it.mrr },
                agg("ndcg@10") { it.ndcg10 },
                MetricValue("queries.scored", scores.size.toDouble(), "count", true),
                MetricValue("cases.skipped", skipped.toDouble(), "count", false,
                    if (skipped > 0) "expected documents missing from index" else ""),
            ),
            rows = rows,
        )
    }

    private fun score(
        case: BenchmarkCase, query: String,
        ranked: List<String>, relevant: Set<String>,
    ): QueryScore {
        fun precisionAt(k: Int): Double {
            if (k <= 0) return 0.0
            val topK = ranked.take(k)
            return topK.count { it in relevant }.toDouble() / k
        }
        fun recallAt(k: Int): Double {
            if (relevant.isEmpty()) return 0.0
            return ranked.take(k).count { it in relevant }.toDouble() / relevant.size
        }
        val firstRelevant = ranked.indexOfFirst { it in relevant }
        val mrr = if (firstRelevant >= 0) 1.0 / (firstRelevant + 1) else 0.0

        return QueryScore(
            caseId = case.id, query = query,
            p1 = precisionAt(1), p5 = precisionAt(5), p10 = precisionAt(10),
            r5 = recallAt(5), r10 = recallAt(10),
            mrr = mrr, ndcg10 = ndcgAt(10, ranked, relevant, case.expectedRank),
        )
    }

    /**
     * NDCG@k. Graded relevance when the case supplies [expectedRank] (most relevant
     * first → gain = position weight); binary relevance otherwise.
     */
    private fun ndcgAt(k: Int, ranked: List<String>, relevant: Set<String>, expectedRank: List<String>): Double {
        fun gain(id: String): Double = when {
            expectedRank.isNotEmpty() -> {
                val idx = expectedRank.indexOf(id)
                if (idx >= 0) (expectedRank.size - idx).toDouble() else 0.0
            }
            id in relevant -> 1.0
            else -> 0.0
        }
        fun dcg(ids: List<String>): Double = ids.take(k).withIndex().sumOf { (i, id) ->
            gain(id) / (ln((i + 2).toDouble()) / ln(2.0))
        }
        val ideal = (if (expectedRank.isNotEmpty()) expectedRank else relevant.toList())
            .sortedByDescending { gain(it) }
        val idcg = dcg(ideal)
        return if (idcg == 0.0) 0.0 else dcg(ranked) / idcg
    }

    private fun fmt(v: Double) = "%.4f".format(java.util.Locale.US, v)
}
