package com.amar.vault.retrieval

import com.amar.vault.QueryPlan
import com.amar.vault.QueryPlanner
import com.amar.vault.VaultConfig
import com.amar.vault.VaultItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
 * Behaviour-sensitive retrieval switches, carried per request so ONE benchmark run can score
 * the same golden queries under each setting
 * ([com.amar.vault.benchmark.RetrievalAblationBenchmark]). Defaults are the production values
 * in [VaultConfig]; production callers never set these, except [onePerDocument] and
 * [relaxEmptyFilter].
 */
data class RetrievalTuning(
    /** Substring/fuzzy rescore only the BM25+FTS candidates instead of scanning the corpus. */
    val candidateBounded: Boolean = VaultConfig.Retrieval.CANDIDATE_BOUNDED,
    /** Keep BM25/vector candidates in engine rank order after Room hydration. */
    val rankOrderHydration: Boolean = VaultConfig.Retrieval.RANK_ORDER_HYDRATION,
    /** Embed the query at its true token length instead of padding to MAX_SEQ_LEN. */
    val queryTrueLength: Boolean = VaultConfig.Embedding.QUERY_TRUE_LENGTH,
    /** False = keyword-only: no vector lane, no late semantic boosts, no model inference. */
    val semanticEnabled: Boolean = true,
    /**
     * Resolve vector hits to the item that owns them and let hits at or above
     * [semanticMinSimilarity] through the text-presence gate, so an item can be found by
     * meaning alone. Off = the legacy vector lane.
     */
    val semanticParentHits: Boolean = VaultConfig.Retrieval.SEMANTIC_PARENT_HITS,
    val semanticMinSimilarity: Float = VaultConfig.Retrieval.SEMANTIC_MIN_SIMILARITY,
    /**
     * One row per document (its best-matching page) instead of one row per page, so a long
     * document cannot fill every result slot. The one switch a production caller does set:
     * the answer path and the entity projection pass false because they work on pages.
     */
    val onePerDocument: Boolean = VaultConfig.Retrieval.ONE_PER_DOCUMENT,
    /**
     * When the query's date or amount filter leaves nothing, search the rest of the query
     * without it instead of returning an empty list. Set by the result list only: an answer
     * must not be built from items outside the period that was asked about.
     */
    val relaxEmptyFilter: Boolean = false,
    /** See [VaultConfig.Retrieval.TYPO_HELP_ONLY_FOR_MISSING_WORDS]. */
    val typoHelpOnlyForMissingWords: Boolean = VaultConfig.Retrieval.TYPO_HELP_ONLY_FOR_MISSING_WORDS,
    /** See [VaultConfig.Retrieval.WORDS_IN_ORDER_BONUS]; false leaves the bonus out. */
    val wordOrderBonus: Boolean = true,
    /** See [VaultConfig.Retrieval.PAGE_WORDS_BEFORE_TAGS]. */
    val pageWordsBeforeTags: Boolean = VaultConfig.Retrieval.PAGE_WORDS_BEFORE_TAGS,
    /** See [VaultConfig.Retrieval.NAME_BONUS]; false leaves the bonus out. */
    val nameBonus: Boolean = true,
    /** See [VaultConfig.Retrieval.WHOLE_WORDS_FIRST]. */
    val wholeWordsFirst: Boolean = VaultConfig.Retrieval.WHOLE_WORDS_FIRST,
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
    val tuning: RetrievalTuning = RetrievalTuning(),
    /**
     * Which items may be returned at all — the search screen's "Images", "Documents", …
     * Applied inside every lane, before the lanes' own caps, so that a kind of item that is
     * outnumbered by another still fills the list when it alone is asked for.
     */
    val only: ((VaultItem) -> Boolean)? = null,
    /**
     * What to look for instead when [RetrievalTuning.relaxEmptyFilter] is set and the plan's
     * period or amount leaves nothing: the query with those words left in, as plain words.
     * Null: the same [query] without the filter.
     */
    val wordsIfFilterEmpty: String? = null,
    /** Abbreviations, lowered, that are to be looked for as typed and not by what they stand for. */
    val plainAbbreviations: Set<String> = emptySet(),
) {
    companion object {
        /**
         * The request for text typed into the search box, whose results are shown as a list.
         * The planner's reading of the text orders and narrows the list, with these differences
         * from an answer's request: "latest"/"oldest" sort it without cutting it to one row; a
         * date or amount that matches nothing is dropped rather than leaving it empty; and a
         * word that is as often part of what is being looked for as an instruction ("last",
         * "first") is searched for like any other word.
         *
         * [asWords] are the keys of the readings the user has taken back
         * ([com.amar.vault.Understood.key]): those words are looked for as words.
         */
        fun forResultList(typed: String, only: ((VaultItem) -> Boolean)? = null): RetrievalRequest =
            forResultList(typed, emptySet(), only)

        fun forResultList(
            typed: String, asWords: Set<String>, only: ((VaultItem) -> Boolean)? = null,
        ): RetrievalRequest {
            val plan = QueryPlanner.parse(typed, forResultList = true, asWords = asWords)
            // The same query with the period and the amount read as words: what is looked for
            // when they leave nothing. "last month" on its own has no other words at all.
            val narrowing = plan.understood.filter { it.narrows }.map { it.key }
            val asPlainWords = if (narrowing.isEmpty()) null
            else QueryPlanner.parse(typed, forResultList = true, asWords = asWords + narrowing).cleanedQuery
            return RetrievalRequest(
                plan.cleanedQuery, plan.copy(limit = null),
                tuning = RetrievalTuning(relaxEmptyFilter = true),
                only = only,
                wordsIfFilterEmpty = asPlainWords,
                plainAbbreviations = com.amar.vault.AcronymDictionary.plainOf(asWords),
            )
        }
    }
}

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
    /**
     * True when nothing in [items] has any of the query's words as typed: every item is there
     * for a word that is spelt nearly the same. The caller can say so instead of presenting
     * look-alikes as matches.
     */
    val similarSpellingsOnly: Boolean = false,
    /**
     * True when the query's period or amount left nothing and [items] are what its words
     * found without it. The caller says so: the list is not what the reading asked for.
     */
    val filterDropped: Boolean = false,
)

/**
 * How complete a progressive emission is. Each stage is the SAME fusion/ranking function run
 * over the lanes that have finished so far — never a second ranking implementation.
 */
enum class RetrievalStage {
    /** Native BM25 only — milliseconds, independent of corpus size and of the embedding model. */
    KEYWORD,
    /** BM25 + substring + fuzzy. No model inference. */
    LEXICAL,
    /** Every lane, including the vector lane and late semantic boosts. Equals [RetrievalService.retrieve]. */
    FINAL,
}

/** One progressive emission. [elapsedMs] is measured from the start of the retrieval call. */
data class RetrievalUpdate(
    val stage: RetrievalStage,
    val result: RetrievalResult,
    val elapsedMs: Long,
)

/**
 * The retrieval responsibility. [com.amar.vault.retrieval.HybridSearchService] is the
 * Phase-1 implementation (lexical + semantic + substring/fuzzy fusion). Callers depend on
 * this interface, not the strategy, so the implementation can evolve or be A/B'd.
 */
interface RetrievalService {
    suspend fun retrieve(request: RetrievalRequest): RetrievalResult

    /**
     * The same retrieval, surfaced as it becomes available so an interactive caller can paint
     * keyword hits without waiting for model inference. Contract:
     *  - the last emission is always [RetrievalStage.FINAL] and its items equal [retrieve]'s;
     *  - earlier stages are emitted only when they carry results the caller has not seen yet;
     *  - only the FINAL emission carries a [RetrievalTrace].
     * Callers that need the definitive ranking (RAG, benchmarks, aggregators) keep using [retrieve].
     */
    fun retrieveProgressive(request: RetrievalRequest): Flow<RetrievalUpdate> = flow {
        val start = System.nanoTime()
        val result = retrieve(request)
        emit(RetrievalUpdate(RetrievalStage.FINAL, result, (System.nanoTime() - start) / 1_000_000))
    }
}
