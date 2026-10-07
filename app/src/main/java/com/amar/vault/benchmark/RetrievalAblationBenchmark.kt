package com.amar.vault.benchmark

import com.amar.vault.retrieval.RetrievalTuning
import com.amar.vault.retrieval.SemanticRetriever

/**
 * Retrieval ablation — the same golden queries, scored through the REAL retrieval pipeline
 * once per behaviour-sensitive switch ([RetrievalTuning]). One run answers the questions that
 * could otherwise only be argued: did the rank-order fix help, does the embedding model earn
 * its size, is the candidate-bounded path safe to turn on, does letting vector hits count
 * help or add noise.
 *
 * Measurement only: every variant goes through `RetrievalService.retrieve` with a per-request
 * tuning; no production default is changed by running this.
 *
 * Reading it: quality metrics are comparable across variants (same queries, same corpus).
 * Latency is measured with the embedding caches cleared before each variant, so a variant
 * that embeds pays for it — but storage and JIT are warm, so treat latency as a comparison
 * between variants, not as what a first search of the day costs.
 */
class RetrievalAblationBenchmark(
    private val evaluator: RetrievalEvaluator,
    private val semantic: SemanticRetriever,
) {

    data class Variant(val name: String, val tuning: RetrievalTuning, val what: String)

    /** Aggregates for one variant; all null when no query could be scored. */
    data class Summary(
        val queries: Int,
        val mrr: Double?,
        val precisionAt1: Double?,
        val recallAt10: Double?,
        val ndcgAt10: Double?,
        val latencyAvgMs: Double?,
    )

    companion object {
        const val BASELINE = "production"

        val VARIANTS: List<Variant> = listOf(
            Variant(BASELINE, RetrievalTuning(), "current defaults"),
            Variant(
                "keywordOnly", RetrievalTuning(semanticEnabled = false),
                "no embedding model at all — what a -PphoneApk build does",
            ),
            Variant(
                "legacyOrder", RetrievalTuning(rankOrderHydration = false),
                "BM25/vector candidates in SQLite id order (behaviour before the rank-order fix)",
            ),
            Variant(
                "paddedQuery", RetrievalTuning(queryTrueLength = false),
                "query embedded padded to 128 tokens (behaviour before true-length embedding)",
            ),
            Variant(
                "bounded", RetrievalTuning(candidateBounded = true),
                "substring/fuzzy rescore the BM25+FTS candidates instead of scanning the corpus",
            ),
            Variant(
                "perPage", RetrievalTuning(onePerDocument = false),
                "one result row per page, so a long document can fill every slot (behaviour before one-per-document)",
            ),
            Variant(
                "typoAlways", RetrievalTuning(typoHelpOnlyForMissingWords = false),
                "near spellings listed for every word, even one the vault has as typed (behaviour before 2026-10-07)",
            ),
            Variant(
                "tagsAsText", RetrievalTuning(pageWordsBeforeTags = false),
                "a word a row is only tagged with scores like a word it says (behaviour before 2026-10-07)",
            ),
            Variant(
                "noWordOrder", RetrievalTuning(wordOrderBonus = false),
                "no bonus for the query's words standing together in order (behaviour before 2026-10-07)",
            ),
            Variant(
                "semanticHits@0.50", RetrievalTuning(semanticParentHits = true, semanticMinSimilarity = 0.50f),
                "vector hits count on their own at cosine ≥ 0.50",
            ),
            Variant(
                "semanticHits@0.55", RetrievalTuning(semanticParentHits = true, semanticMinSimilarity = 0.55f),
                "vector hits count on their own at cosine ≥ 0.55",
            ),
            Variant(
                "semanticHits@0.60", RetrievalTuning(semanticParentHits = true, semanticMinSimilarity = 0.60f),
                "vector hits count on their own at cosine ≥ 0.60",
            ),
        )

        fun summarize(scores: List<RetrievalEvaluator.QueryScore>, latenciesMs: List<Long>): Summary =
            if (scores.isEmpty()) Summary(0, null, null, null, null, null)
            else Summary(
                queries = scores.size,
                mrr = scores.map { it.mrr }.average(),
                precisionAt1 = scores.map { it.p1 }.average(),
                recallAt10 = scores.map { it.r10 }.average(),
                ndcgAt10 = scores.map { it.ndcg10 }.average(),
                latencyAvgMs = if (latenciesMs.isEmpty()) null else latenciesMs.average(),
            )
    }

    suspend fun run(cases: List<BenchmarkCase>): BenchmarkSection {
        // Untimed pass so storage, JIT and the native engines are equally warm for every variant.
        evaluator.scoreAll(cases)

        val semanticUsable = semantic.isReady
        val metrics = mutableListOf(
            MetricValue(
                "semantic.available", if (semanticUsable) 1.0 else 0.0, "bool", true,
                if (semanticUsable) "" else
                    "no usable embedding model on this install — every variant runs keyword-only, so the semantic variants equal keywordOnly by construction",
            )
        )
        val rows = mutableListOf<Map<String, String>>()
        var baseline: Summary? = null

        for (variant in VARIANTS) {
            semantic.clearCaches() // each variant pays its own embedding cost
            val scored = evaluator.scoreAll(cases, variant.tuning)
            val summary = summarize(scored.scores, scored.latenciesMs)
            if (variant.name == BASELINE) baseline = summary

            val note = if (summary.queries == 0)
                "no scorable cases (dataset empty or documents not indexed)" else variant.what
            fun quality(metric: String, value: Double?) =
                MetricValue("${variant.name}.$metric", value, "ratio", true, note)
            metrics += quality("mrr", summary.mrr)
            metrics += quality("precision@1", summary.precisionAt1)
            metrics += quality("recall@10", summary.recallAt10)
            metrics += quality("ndcg@10", summary.ndcgAt10)
            metrics += MetricValue(
                "${variant.name}.latency.avg", summary.latencyAvgMs, "ms", false,
                if (summary.queries == 0) note else "embedding caches cleared before this variant; storage warm",
            )

            rows += mapOf(
                "variant" to variant.name,
                "what" to variant.what,
                "queries" to summary.queries.toString(),
                "mrr" to fmt(summary.mrr),
                "precision@1" to fmt(summary.precisionAt1),
                "recall@10" to fmt(summary.recallAt10),
                "ndcg@10" to fmt(summary.ndcgAt10),
                "latencyAvgMs" to (summary.latencyAvgMs?.let { "%.1f".format(java.util.Locale.US, it) } ?: ""),
                "mrrVsProduction" to delta(summary.mrr, baseline?.mrr),
            )
        }

        metrics += MetricValue("queries.scored", (baseline?.queries ?: 0).toDouble(), "count", true)
        return BenchmarkSection(
            id = "retrieval.ablation",
            title = "Retrieval ablation (same golden queries under each retrieval switch)",
            metrics = metrics,
            rows = rows,
        )
    }

    private fun fmt(v: Double?) = v?.let { "%.4f".format(java.util.Locale.US, it) } ?: ""

    private fun delta(v: Double?, base: Double?) =
        if (v == null || base == null) "" else "%+.4f".format(java.util.Locale.US, v - base)
}
