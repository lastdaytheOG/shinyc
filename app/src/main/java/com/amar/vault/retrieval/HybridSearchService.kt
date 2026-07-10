package com.amar.vault.retrieval

import com.amar.vault.ConstraintOperator
import com.amar.vault.QueryPlan
import com.amar.vault.SortOrder
import com.amar.vault.VaultConfig
import com.amar.vault.VaultItem
import com.amar.vault.VaultLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * Phase-1 retrieval implementation: the exact pre-existing hybrid pipeline (BM25 + HNSW +
 * substring + fuzzy → RRF fusion → metadata boosts → text-presence gating → temporal
 * re-rank), moved verbatim out of SearchViewModel behind [RetrievalService].
 *
 * What changed vs. the old in-ViewModel method (all behaviour-preserving):
 *  - depends on ports/repository, not concretes → testable, UI-independent;
 *  - N+1 queries (strict-DATE fallback, temporal re-rank) are batched via [SearchRepository];
 *  - candidate caps read from [VaultConfig.Retrieval] (frozen at prior values);
 *  - final fused order has a stable secondary key (ties → id) for deterministic tests;
 *  - optional [RetrievalTrace] emitted from the real path (zero cost when no sink).
 *
 * The ONLY behaviour-sensitive change — candidate-bounded substring/fuzzy — is gated behind
 * [VaultConfig.Retrieval.CANDIDATE_BOUNDED] (default false) until Phase 0B validates it.
 * Cancellation is cooperative: lanes run in a coroutineScope and honour ensureActive().
 */
class HybridSearchService(
    private val repository: SearchRepository,
    private val lexical: LexicalRetriever,
    private val semantic: SemanticRetriever,
    private val boostConfig: BoostConfig,
) : RetrievalService {

    private enum class QueryType { EXACT_KEYWORD, SEMANTIC_PHRASE, GIBBERISH }

    private companion object {
        /** Compiled once — the lanes previously re-compiled Regex("\\s+") per item/query. */
        val WHITESPACE = Regex("\\s+")
    }

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult = coroutineScope {
        // Sprint 4A: NFC at the single query-side boundary, matching the NFC applied at
        // both ingestion boundaries — typed queries compare canonically against stored text.
        val q = com.amar.vault.UnicodeText.nfc(request.query)
        val plan = request.plan
        val budget = request.budget
        val sink = request.traceSink
        val queryType = classifyQuery(q)
        val qLower = q.lowercase().trim()

        // Sprint 4B.1: deterministic acronym expansion computed ONCE at THIS single query
        // boundary into an immutable [ExpandedQuery]. The original query (q/qLower) is
        // preserved verbatim for classification, length gates, and exact-phrase scoring;
        // only the recall-bearing inputs below consume the expansion. For a query without a
        // recognized acronym the expanded/joined forms equal the original, so normal/Hindi/
        // PDF/screenshot queries are unchanged (behaviour identical to Sprint 4B — this is a
        // representation change, not a behaviour change).
        val expanded = com.amar.vault.AcronymDictionary.analyze(q)
        val expandedQuery = expanded.joined()                 // BM25 + semantic embed
        val expandedLower = expandedQuery.lowercase().trim()  // substring + fuzzy lanes
        if (expanded.containsKnownAcronym) {
            VaultLog.d("HybridSearch",
                "acronym-expand q='${VaultLog.redact(expanded.originalQuery)}' -> " +
                "'${VaultLog.redact(expanded.expandedTerms.joinToString(" | "))}'")
        }

        // ── 0. STRICT constraint pushdown (SQLite) — batched fallback ──────
        val strictIds = computeStrictIds(plan)
        if (strictIds != null && strictIds.isEmpty()) {
            return@coroutineScope RetrievalResult(emptyList())
        }

        // Blank query + strict constraints → return filtered/sorted set.
        if (qLower.isBlank() && strictIds != null) {
            val items = blankStrict(strictIds, plan)
            return@coroutineScope RetrievalResult(items)
        }

        fun List<VaultItem>.applyStrictFilter() =
            if (strictIds != null) filter { it.id in strictIds } else this

        // ── 1–4. Candidate lanes ──────────────────────────────────────────
        val bm25Deferred = async(Dispatchers.IO) {
            try {
                val ids = lexical.bm25(expandedQuery, budget.bm25)
                repository.getByIds(ids).applyStrictFilter()
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "BM25 error: ${e.message}"); emptyList()
            }
        }

        val vectorDeferred = async(Dispatchers.IO) {
            if (queryType == QueryType.GIBBERISH) return@async emptyList<VaultItem>()
            // Sprint 4B: the <4-char skip stays for junk queries, but a recognized acronym
            // ("AI", "ML", "PDF") now reaches the semantic lane via its expansion. (A <4-char
            // query is single-token, so containsKnownAcronym == the old isKnownAcronym check.)
            if (qLower.length < 4 && !expanded.containsKnownAcronym)
                return@async emptyList<VaultItem>()
            if (!semantic.isReady) return@async emptyList<VaultItem>()
            val vec = semantic.embedQuery(expandedQuery)
            ensureActive()
            val ids = semantic.searchVectors(vec, budget.vector)
            if (ids.isEmpty()) emptyList() else repository.getByIds(ids).applyStrictFilter()
        }

        // Substring/fuzzy base set: full snapshot (classic) or the bounded candidate union.
        // Loaded once and shared by both lanes.
        val substringFuzzyBase: List<VaultItem> =
            if (VaultConfig.Retrieval.CANDIDATE_BOUNDED)
                boundedBaseSet(expandedQuery, budget, bm25Deferred.await()).applyStrictFilter()
            else
                repository.allItemsSnapshot().applyStrictFilter()

        // Each item's ocrText is lowercased ONCE and shared by both lanes. Previously the
        // substring lane lowercased every item twice (filter + sort) and the fuzzy lane a
        // third time — three full-corpus String allocations per query, now one. Matching
        // semantics are unchanged (same default-locale lowercase on both text and query).
        val loweredBaseDeferred = async(Dispatchers.IO) {
            substringFuzzyBase.map { it to it.ocrText.lowercase() }
        }

        val substringDeferred = async(Dispatchers.IO) { substringLane(loweredBaseDeferred.await(), expandedLower, budget.substring) }
        val fuzzyDeferred = async(Dispatchers.IO) { fuzzyLane(loweredBaseDeferred.await(), expandedLower, budget.fuzzy) }

        val bm25Results = bm25Deferred.await()
        val vectorResults = vectorDeferred.await()
        val substringResults = substringDeferred.await()
        val fuzzyResults = fuzzyDeferred.await()

        VaultLog.d("HybridSearch",
            "q='${VaultLog.redact(q)}' bm25=${bm25Results.size} vec=${vectorResults.size} " +
            "sub=${substringResults.size} fuzzy=${fuzzyResults.size}")

        if (bm25Results.isEmpty() && vectorResults.isEmpty() &&
            substringResults.isEmpty() && fuzzyResults.isEmpty()) {
            return@coroutineScope RetrievalResult(emptyList())
        }

        // ── 5. Late embedding for semantic document queries ───────────────
        val semanticDocBoosts = lateDocumentBoosts(queryType, qLower, q, bm25Results, substringResults, this)

        // ── 6–8. Fuse, boost, gate, re-rank ───────────────────────────────
        val fusion = fuseAndRank(bm25Results, vectorResults, substringResults, fuzzyResults,
            semanticDocBoosts, plan, qLower, expanded.gateTerms)

        val trace = sink?.let {
            RetrievalTrace(
                query = q,
                lanes = listOf(
                    LaneTrace("bm25", bm25Results.map { r -> r.id }),
                    LaneTrace("vector", vectorResults.map { r -> r.id }),
                    LaneTrace("substring", substringResults.map { r -> r.id }),
                    LaneTrace("fuzzy", fuzzyResults.map { r -> r.id }),
                ),
                fusedScores = fusion.scores,
                finalOrder = fusion.items.map { r -> r.id },
                notes = if (VaultConfig.Retrieval.CANDIDATE_BOUNDED) listOf("candidate-bounded") else emptyList(),
            ).also(it::accept)
        }
        RetrievalResult(fusion.items, trace)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Strict-constraint pushdown (batched)
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun computeStrictIds(plan: QueryPlan?): Set<String>? {
        if (plan == null || plan.strict.isEmpty()) return null
        var currentIds: MutableSet<String>? = null
        for (constraint in plan.strict) {
            val matchingIds = mutableSetOf<String>()
            if (constraint.type == "DATE" && constraint.operator == ConstraintOperator.BETWEEN &&
                constraint.timestampValue != null) {
                val lo = constraint.timestampValue[0]; val hi = constraint.timestampValue[1]
                matchingIds.addAll(repository.dateRangeItemIds(lo, hi))
                // Batched fallback: items with NO date metadata but timestamp in range.
                val haveDate = repository.itemIdsHavingDate()
                repository.allItemsSnapshot().forEach { item ->
                    if (item.id !in haveDate && item.timestamp in lo..hi) matchingIds.add(item.id)
                }
            } else if (constraint.type == "AMOUNT" && constraint.operator == ConstraintOperator.GREATER_THAN &&
                constraint.numericValue != null) {
                matchingIds.addAll(repository.amountGreaterThanIds(constraint.numericValue))
            }
            currentIds = if (currentIds == null) matchingIds else currentIds.apply { retainAll(matchingIds) }
            if (currentIds.isEmpty()) break
        }
        return currentIds
    }

    private suspend fun blankStrict(strictIds: Set<String>, plan: QueryPlan?): List<VaultItem> {
        var filtered = repository.allItemsSnapshot().filter { it.id in strictIds }
        val sortOrder = plan?.sortIntent
        if (sortOrder != null) {
            val dates = repository.effectiveDates(filtered)
            filtered = filtered.sortedWith(dateComparator(sortOrder, dates))
        }
        val limit = plan?.limit
        return if (limit != null) filtered.take(limit) else filtered
    }

    // ════════════════════════════════════════════════════════════════════════
    // Lanes
    // ════════════════════════════════════════════════════════════════════════

    /** Lanes receive (item, loweredOcrText) pairs — the lowercase is computed once upstream. */
    private fun substringLane(items: List<Pair<VaultItem, String>>, qLower: String, limit: Int): List<VaultItem> {
        if (qLower.length < 2) return emptyList()
        val words = qLower.split(WHITESPACE).filter { it.isNotEmpty() }
        // Same result set and order as the previous filter → sortedByDescending → take:
        // any-word match to qualify, stable sort by match count descending, first [limit].
        val matched = ArrayList<Pair<VaultItem, Int>>()
        for ((item, text) in items) {
            var hits = 0
            for (w in words) if (text.contains(w)) hits++
            if (hits > 0) matched.add(item to hits)
        }
        matched.sortByDescending { it.second } // stable, like sortedByDescending
        return matched.take(limit).map { it.first }
    }

    private fun fuzzyLane(items: List<Pair<VaultItem, String>>, qLower: String, limit: Int): List<VaultItem> {
        if (qLower.length < 4) return emptyList()
        val words = qLower.split(WHITESPACE).filter { it.length >= 3 }
        if (words.isEmpty()) return emptyList()
        // filter{...}.take(limit) ≡ first [limit] matches in list order — so stop scanning
        // (and running levenshtein) once [limit] matches are found. Output is identical.
        val out = ArrayList<VaultItem>(limit)
        for ((item, text) in items) {
            if (out.size >= limit) break
            val docWords = text.split(WHITESPACE).filter { it.length >= 3 }
            val matches = words.any { qw ->
                val md = if (qw.length <= 4) 1 else 2
                docWords.any { dw -> kotlin.math.abs(dw.length - qw.length) <= md && levenshtein(qw, dw) <= md }
            }
            if (matches) out.add(item)
        }
        return out
    }

    /** Candidate union for the bounded path (gated). Conditional fallback to full scan
     *  only when the index lanes recall nothing — bounded frequency, not per-query O(N). */
    private suspend fun boundedBaseSet(q: String, budget: CandidateBudget, bm25Hydrated: List<VaultItem>): List<VaultItem> {
        val ftsIds = lexical.fts(q, budget.fts)
        val unionIds = (bm25Hydrated.map { it.id } + ftsIds).distinct()
        val union = repository.getByIds(unionIds)
        return if (union.isEmpty()) repository.allItemsSnapshot() else union
    }

    private suspend fun lateDocumentBoosts(
        queryType: QueryType, qLower: String, q: String,
        bm25Results: List<VaultItem>, substringResults: List<VaultItem>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Map<String, Double> {
        if (queryType != QueryType.SEMANTIC_PHRASE || qLower.length < 6) return emptyMap()
        val docTypes = setOf("pdf", "word", "excel", "epub")
        val docCandidates = (bm25Results + substringResults)
            .filter { it.itemType in docTypes }
            .distinctBy { it.id }
            .take(VaultConfig.Retrieval.LATE_EMBED_DOC_LIMIT)
        if (docCandidates.isEmpty()) return emptyMap()
        val boosts = mutableMapOf<String, Double>()
        try {
            val queryVec = semantic.embedQuery(q)
            docCandidates.forEach { item ->
                scope.ensureActive()
                val chunkText = item.ocrText.substringBefore("\n[").trim()
                if (chunkText.length >= 20) {
                    val sim = cosineSimilarity(queryVec, semantic.embedText(chunkText))
                    if (sim > VaultConfig.Retrieval.LATE_EMBED_SIM_THRESHOLD) boosts[item.id] = sim.toDouble()
                }
            }
        } catch (e: Exception) {
            VaultLog.e("HybridSearch", "Late embedding error: ${e.message}")
        }
        return boosts
    }

    // ════════════════════════════════════════════════════════════════════════
    // Fusion + boosts + gating + temporal re-rank
    // ════════════════════════════════════════════════════════════════════════

    private class Fusion(val items: List<VaultItem>, val scores: Map<String, Double>)

    private suspend fun fuseAndRank(
        bm25Results: List<VaultItem>, vectorResults: List<VaultItem>,
        substringResults: List<VaultItem>, fuzzyResults: List<VaultItem>,
        semanticDocBoosts: Map<String, Double>, plan: QueryPlan?, qLower: String,
        // Sprint 4B.1: presence-gate vocabulary = distinct original + expansion words
        // ([ExpandedQuery.gateTerms]). For a query with no acronym this is exactly the set
        // of qLower's words, so gating is unchanged for normal queries.
        gateWords: Set<String>,
    ): Fusion {
        val cfg = VaultConfig.Retrieval
        val weights = mapOf(
            "bm25" to cfg.WEIGHT_BM25, "vector" to cfg.WEIGHT_VECTOR,
            "substring" to cfg.WEIGHT_SUBSTRING, "fuzzy" to cfg.WEIGHT_FUZZY,
        )
        val rrfScores = mutableMapOf<String, Double>()
        val itemMap = mutableMapOf<String, VaultItem>()

        fun scoreLane(results: List<VaultItem>, lane: String) {
            val w = weights[lane] ?: 1.0
            results.forEachIndexed { rank, item ->
                itemMap[item.id] = item
                rrfScores[item.id] = (rrfScores[item.id] ?: 0.0) + w * (1.0 / (cfg.RRF_K + rank))
            }
        }
        scoreLane(bm25Results, "bm25"); scoreLane(vectorResults, "vector")
        scoreLane(substringResults, "substring"); scoreLane(fuzzyResults, "fuzzy")

        for ((id, sim) in semanticDocBoosts) {
            rrfScores[id] = (rrfScores[id] ?: 0.0) + sim * cfg.LATE_SEMANTIC_WEIGHT
        }

        // PREFERRED metadata boosts (multiplicative) — batched repository lookups.
        if (plan != null && plan.preferred.isNotEmpty()) {
            for (constraint in plan.preferred) {
                val boost = when (constraint.type) {
                    "ORGANIZATION" -> boostConfig.organization
                    "PAYMENT_APP" -> boostConfig.paymentApp
                    "SOURCE_APP" -> boostConfig.sourceApp
                    "CATEGORY" -> boostConfig.category
                    "DOCUMENT_TYPE" -> boostConfig.documentType
                    else -> boostConfig.defaultSoft
                }
                val boostedIds = repository.itemIdsByTypeValue(constraint.type, constraint.value).toSet()
                for (id in boostedIds) if (rrfScores.containsKey(id)) rrfScores[id] = rrfScores[id]!! * boost
            }
        }

        // Text-presence gating (identical to prior logic).
        val bm25Ids = bm25Results.map { it.id }.toSet()
        val substringIds = substringResults.map { it.id }.toSet()
        val fuzzyIds = fuzzyResults.map { it.id }.toSet()
        val queryWords = gateWords
        val toRemove = mutableListOf<String>()
        for ((id, item) in itemMap) {
            val text = item.ocrText.lowercase()
            val matched = queryWords.count { text.contains(it) }
            if (matched > 0) {
                if (text.contains(qLower)) rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.EXACT_PHRASE_BONUS
                val ratio = matched.toDouble() / queryWords.size
                rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.WORD_RATIO_BONUS * ratio
            } else if (!(id in bm25Ids || id in substringIds || id in fuzzyIds)) {
                toRemove.add(id)
            }
        }
        toRemove.forEach { itemMap.remove(it); rrfScores.remove(it) }

        // Sort with a STABLE secondary key (ties → id) for deterministic results.
        val ranked = itemMap.entries
            .sortedWith(compareByDescending<Map.Entry<String, VaultItem>> { rrfScores[it.key] ?: 0.0 }
                .thenBy { it.key })
            .map { it.value }

        var finalResults = ranked.take(cfg.FUSION_TOPN)
        val sortOrder = plan?.sortIntent
        if (sortOrder != null) {
            val topN = ranked.take(cfg.RERANK_TOPN)
            val dates = repository.effectiveDates(topN)
            finalResults = topN.sortedWith(dateComparator(sortOrder, dates))
        }
        val limit = plan?.limit
        if (limit != null) finalResults = finalResults.take(limit)
        return Fusion(finalResults, rrfScores)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers (moved verbatim from the ViewModel)
    // ════════════════════════════════════════════════════════════════════════

    /** Descending/ascending by effective date, with a stable id tie-break. */
    private fun dateComparator(order: SortOrder, dates: Map<String, Long>): Comparator<VaultItem> {
        val byDate = Comparator<VaultItem> { a, b ->
            val da = dates[a.id] ?: a.timestamp; val db = dates[b.id] ?: b.timestamp
            if (order == SortOrder.DESC) db.compareTo(da) else da.compareTo(db)
        }
        return byDate.thenBy { it.id }
    }

    private fun classifyQuery(q: String): QueryType {
        if (q.matches(Regex("^[^a-zA-Z\\u0900-\\u097F]+$"))) return QueryType.GIBBERISH
        if (q.length == 1) return QueryType.EXACT_KEYWORD
        if (q.length < 6 && q.matches(Regex("^[a-zA-Z]{1,3}\\d+.*$"))) return QueryType.GIBBERISH
        return if (q.contains(" ")) QueryType.SEMANTIC_PHRASE else QueryType.EXACT_KEYWORD
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denom > 0f) dot / denom else 0f
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0; if (a.isEmpty()) return b.length; if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }; var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = curr; curr = t
        }
        return prev[b.length]
    }
}
