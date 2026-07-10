package com.amar.vault.retrieval

import com.amar.vault.QueryPlan
import com.amar.vault.VaultConfig
import com.amar.vault.VaultItem

/**
 * Per-capability candidate budget. Guarantees the candidate union stays bounded
 * regardless of what individual engines return. Defaults are frozen at the pre-Phase-1
 * effective caps (see [VaultConfig.Retrieval]) so retrieval stays result-identical.
 */
data class CandidateBudget(
    val bm25: Int = VaultConfig.Retrieval.BM25_BUDGET,
    val vector: Int = VaultConfig.Retrieval.VECTOR_BUDGET,
    val substring: Int = VaultConfig.Retrieval.SUBSTRING_BUDGET,
    val fuzzy: Int = VaultConfig.Retrieval.FUZZY_BUDGET,
    val fts: Int = VaultConfig.Retrieval.FTS_BUDGET,
)

/**
 * Stable request object so future retrieval enhancements (new signals, filters) do not
 * churn method signatures.
 */
data class RetrievalRequest(
    val query: String,
    val plan: QueryPlan? = null,
    val budget: CandidateBudget = CandidateBudget(),
    /** When non-null, retrieval records a structured trace of the REAL execution path. */
    val traceSink: RetrievalTraceSink? = null,
)

/** One lane's contribution — the actual candidate ids it produced, in rank order. */
data class LaneTrace(val lane: String, val candidateIds: List<String>)

/**
 * Structured trace of exactly what retrieval executed. Emitted from the real path (never
 * a second scoring implementation) and consumed by the Phase 0B diagnostics/reports.
 */
data class RetrievalTrace(
    val query: String,
    val lanes: List<LaneTrace>,
    val fusedScores: Map<String, Double>,
    val finalOrder: List<String>,
    val notes: List<String> = emptyList(),
)

/** Opt-in sink. Absent ⇒ no trace objects are built (zero overhead when disabled). */
fun interface RetrievalTraceSink {
    fun accept(trace: RetrievalTrace)
}

data class RetrievalResult(
    val items: List<VaultItem>,
    val trace: RetrievalTrace? = null,
)

/**
 * The retrieval responsibility. [com.amar.vault.retrieval.HybridSearchService] is the
 * Phase-1 implementation (lexical + semantic + substring/fuzzy fusion). Callers depend on
 * this interface, not the strategy, so the implementation can evolve or be A/B'd.
 */
interface RetrievalService {
    suspend fun retrieve(request: RetrievalRequest): RetrievalResult
}
