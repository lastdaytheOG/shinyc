package com.amar.vault.retrieval

import com.amar.vault.ConstraintOperator
import com.amar.vault.QueryPlan
import com.amar.vault.SortOrder
import com.amar.vault.VaultConfig
import com.amar.vault.VaultItem
import com.amar.vault.VaultLog
import com.amar.vault.isDocumentPiece
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
        /** Whatever is not part of a word: not a letter, a combining mark or a digit. */
        val NOT_A_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
        /** More than a query has words: the scan lane's sort key is `words said * this + words`. */
        const val SAID_RADIX = 1024
    }

    override suspend fun retrieve(request: RetrievalRequest): RetrievalResult =
        executeRelaxing(request, onStage = null)

    /**
     * Progressive form of [retrieve]: the same [execute] run, with the fused ranking of the
     * lanes finished so far surfaced as KEYWORD (BM25 only) and LEXICAL (+ substring/fuzzy)
     * before the FINAL result. The lexical stages need no model inference, so the search box
     * paints them while the query embedding and late passage embeddings are still running.
     */
    override fun retrieveProgressive(request: RetrievalRequest): Flow<RetrievalUpdate> = flow {
        val start = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - start) / 1_000_000
        val timings = StringBuilder()
        val result = executeRelaxing(request) { stage, items ->
            val ms = elapsedMs()
            timings.append(stage.name.lowercase()).append('=').append(ms).append("ms ")
            emit(RetrievalUpdate(stage, RetrievalResult(items), ms))
        }
        val total = elapsedMs()
        VaultLog.d("HybridSearch", "progressive ${timings}final=${total}ms")
        emit(RetrievalUpdate(RetrievalStage.FINAL, result, total))
    }

    /**
     * [execute]; and for a caller that asked ([RetrievalTuning.relaxEmptyFilter]), when the
     * query's date or amount filter leaves nothing, the rest of the query once more without it.
     */
    private suspend fun executeRelaxing(
        request: RetrievalRequest,
        onStage: (suspend (RetrievalStage, List<VaultItem>) -> Unit)?,
    ): RetrievalResult {
        val result = execute(request, onStage)
        val plan = request.plan
        if (result.items.isNotEmpty() || !request.tuning.relaxEmptyFilter ||
            plan == null || plan.strict.isEmpty() || request.query.isBlank()) return result
        VaultLog.d("HybridSearch", "filter matched nothing — searching the text without it")
        return execute(request.copy(plan = plan.copy(strict = emptyList())), onStage)
    }

    /**
     * The single retrieval path. [onStage] is null for [retrieve] (no intermediate fusion is
     * computed at all) and non-null for [retrieveProgressive]; it is only ever invoked from
     * this coroutine, never from inside a lane, so a `flow { }` collector may emit from it.
     */
    private suspend fun execute(
        request: RetrievalRequest,
        onStage: (suspend (RetrievalStage, List<VaultItem>) -> Unit)?,
    ): RetrievalResult = coroutineScope {
        // Sprint 4A: NFC at the single query-side boundary, matching the NFC applied at
        // both ingestion boundaries — typed queries compare canonically against stored text.
        val q = com.amar.vault.UnicodeText.nfc(request.query)
        val plan = request.plan
        val budget = request.budget
        val tuning = request.tuning
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

        // The words as typed. Acronym expansions are not among them: they are the app's
        // addition, and typo help is for what the user wrote.
        val typedWords = QueryWord.of(qLower)
        // The query with the punctuation typed onto its words taken off: "last working days?"
        // is the phrase "last working days".
        val barePhrase = typedWords.joinToString(" ") { it.bare }
        // The parts of words typed with punctuation inside them ("node.js" is "node" and "js").
        val bareWords = expanded.gateTerms.flatMap { it.split(NOT_A_WORD) }.filter { it.length >= 2 }.toSet()
        val wordOrder = typedWords.flatMap { it.typed.split(NOT_A_WORD) }.filter { it.isNotEmpty() }
            .takeIf { tuning.wordOrderBonus && it.size >= 2 }?.let(::WordsInOrder)

        // ── 0. STRICT constraint pushdown (SQLite) — batched fallback ──────
        val strictIds = computeStrictIds(plan)
        if (strictIds != null && strictIds.isEmpty()) {
            return@coroutineScope RetrievalResult(emptyList())
        }

        val only = request.only

        // Blank query + strict constraints → return filtered/sorted set.
        if (qLower.isBlank() && strictIds != null) {
            val items = blankStrict(strictIds, plan, only)
            return@coroutineScope RetrievalResult(items)
        }

        fun List<VaultItem>.applyStrictFilter(): List<VaultItem> {
            val inPeriod = if (strictIds != null) filter { it.id in strictIds } else this
            return if (only != null) inPeriod.filter(only) else inPeriod
        }

        // ── 1–4. Candidate lanes ──────────────────────────────────────────
        val bm25Deferred = async(Dispatchers.IO) {
            try {
                // One row per document: read deeper into the engine's ranking, so the budget
                // is filled with different documents rather than one document's pages. The
                // same when only one kind of item is wanted: the engine does not know kinds,
                // and its first rows may all be of another.
                val fetch = if (tuning.onePerDocument || only != null)
                    budget.bm25 * VaultConfig.Retrieval.BM25_DOC_OVERFETCH else budget.bm25
                hydrateRanked(lexical.bm25(expandedQuery, fetch), tuning).applyStrictFilter()
                    .let { if (tuning.onePerDocument) it.firstPerDocument(budget.bm25) else it }
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "BM25 error: ${e.message}"); emptyList()
            }
        }

        val vectorDeferred = async(Dispatchers.IO) {
            if (!tuning.semanticEnabled) return@async emptyList<VaultItem>()
            if (queryType == QueryType.GIBBERISH) return@async emptyList<VaultItem>()
            // Sprint 4B: the <4-char skip stays for junk queries, but a recognized acronym
            // ("AI", "ML", "PDF") now reaches the semantic lane via its expansion. (A <4-char
            // query is single-token, so containsKnownAcronym == the old isKnownAcronym check.)
            if (qLower.length < 4 && !expanded.containsKnownAcronym)
                return@async emptyList<VaultItem>()
            // An empty vector index can return nothing, so the query embedding (one full model
            // inference) would be pure waste — skip it. Result-identical by construction.
            if (!semantic.isReady || !semantic.hasVectors) return@async emptyList<VaultItem>()
            try {
                val vec = semantic.embedQuery(expandedQuery, tuning.queryTrueLength)
                ensureActive()
                val hits = if (tuning.semanticParentHits)
                    ownedSemanticHits(vec, budget.vector, tuning.semanticMinSimilarity)
                else
                    hydrateRanked(semantic.searchVectors(vec, budget.vector), tuning)
                hits.applyStrictFilter()
                    .let { if (tuning.onePerDocument) it.firstPerDocument(budget.vector) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same policy as the BM25 lane: a failed lane contributes nothing instead of
                // failing the whole search (a missing/broken embedding model must still leave
                // keyword search working).
                VaultLog.e("HybridSearch", "Vector lane error: ${e.message}"); emptyList<VaultItem>()
            }
        }

        // Substring/fuzzy base set: full snapshot (classic) or the bounded candidate union.
        // Loaded once and shared by both lanes — inside a lane, so the KEYWORD stage below is
        // never queued behind a full-corpus read. Each item's ocrText is lowercased ONCE here
        // (previously three full-corpus String allocations per query). Matching semantics are
        // unchanged (same default-locale lowercase on both text and query). The searchable
        // name is lowered alongside it; every chunk of a document carries the same file name,
        // so it is built once per name rather than once per row.
        val loweredBaseDeferred = async(Dispatchers.IO) {
            val base = if (tuning.candidateBounded)
                boundedBaseSet(expandedQuery, budget, bm25Deferred.await())
            else
                repository.allItemsSnapshot()
            val fileNames = HashMap<String, String>()
            base.applyStrictFilter().map { item ->
                val name = when {
                    item.title != null -> loweredName(item)
                    !item.itemType.isDocument -> ""
                    else -> fileNames.getOrPut(item.sourceFile) { loweredName(item) }
                }
                Candidate(item, RowText.of(item), name)
            }
        }

        val substringDeferred = async(Dispatchers.IO) {
            substringLane(loweredBaseDeferred.await(), expandedLower, budget.substring, tuning.onePerDocument,
                tuning.pageWordsBeforeTags)
        }
        // A typed word that is in the vault as typed needs no typo help: its look-alikes are
        // other words ("cloud" for "claude"), not misspellings of it. Null = the previous
        // behaviour, typo help for every word of the expanded query.
        val legacyTypoHelp = !tuning.typoHelpOnlyForMissingWords
        val missingWordsDeferred = async(Dispatchers.IO) {
            if (legacyTypoHelp) null
            else substringDeferred.await().wordsFound.let { found ->
                typedWords.filter { it.typed !in found }.map { it.bare }.distinct()
            }
        }
        val fuzzyDeferred = async(Dispatchers.IO) {
            val words = missingWordsDeferred.await() ?: expandedLower.split(WHITESPACE)
            fuzzyLane(loweredBaseDeferred.await(), expandedLower, words, budget.fuzzy, tuning.onePerDocument,
                roots = !legacyTypoHelp, pageFirst = tuning.pageWordsBeforeTags)
        }

        // Progressive stages: the SAME fuseAndRank over the lanes finished so far. A stage is
        // surfaced only when it shows the caller an order it has not seen yet.
        var shownIds: List<String>? = null
        suspend fun surface(stage: RetrievalStage, fusion: Fusion) {
            val ids = fusion.items.map { it.id }
            if (ids.isEmpty() || ids == shownIds) return
            shownIds = ids
            onStage?.invoke(stage, fusion.items)
        }

        // What a row with none of the query's words in it may stay for (see [Gate]). The scan
        // decides which typed words are missing from the vault; until it has, the keyword
        // stage shows only rows that do have a query word, rather than flash look-alikes.
        val gateWords = expanded.gateTerms.map(::QueryWord)
        fun gate(missingWords: List<String>) = Gate(
            gateWords, barePhrase, bareWords, wordOrder, legacyTypoHelp, tuning.pageWordsBeforeTags,
            nearMissing = missingWords.filter { it.length >= 3 }.takeIf { it.isNotEmpty() }
                ?.let { FuzzyMatcher(it, roots = true) },
        )

        // One row per document: each lane picked its own best page, and fusion scores by row
        // id, so two lanes naming one document by different pages would count as two results.
        // Every lane therefore names a document by one page: the page the FIRST lane to find
        // it chose — keyword, substring, typo, then vector, the order they are awaited below.
        //
        // Unless that page has less to show for the query than the page a later keyword lane
        // names ([Gate.standing]). The engine names a page for sharing three-letter pieces
        // with a word it does not know as a word; for "brenda" that can as well be a page
        // that says "break", "current" and "standard" as the one that says "Brendan". Left as
        // the document's row, it is a result with nothing on it — or, once the gate has looked
        // at it, no result at all, and the document that says "Brendan" is gone from the list.
        // It names a page that is only tagged with a word as readily as one that says it, too.
        // A page that says a query word is never swapped for another once it has been shown.
        val documentRow = HashMap<String, VaultItem>()
        val standings = HashMap<String, Int>()
        fun claimDocuments(lane: List<VaultItem>, judge: Gate?) {
            if (!tuning.onePerDocument) return
            fun standing(row: VaultItem) =
                standings.getOrPut(row.id) { judge!!.standing(RowText.of(row), loweredName(row)) }
            for (row in lane) {
                val document = documentKey(row)
                val kept = documentRow[document]
                if (kept == null || (judge != null && kept.id != row.id && standing(row) > standing(kept))) {
                    documentRow[document] = row
                }
            }
        }
        fun List<VaultItem>.asDocumentRows(): List<VaultItem> =
            if (!tuning.onePerDocument) this else map { documentRow.getValue(documentKey(it)) }

        val bm25Lane = bm25Deferred.await()
        claimDocuments(bm25Lane, judge = null)
        if (onStage != null && bm25Lane.isNotEmpty()) {
            surface(RetrievalStage.KEYWORD, fuseAndRank(bm25Lane.asDocumentRows(), emptyList(), emptyList(), emptyList(),
                emptyMap(), plan, qLower, gate(emptyList()), keepVectorOnly = false))
        }

        val substringLane = substringDeferred.await().hits
        val fuzzyLane = fuzzyDeferred.await()
        val lexicalGate = gate(missingWordsDeferred.await().orEmpty())
        // With typo help for every word and tags read as text, a document keeps the first page
        // named, as before.
        val judge = lexicalGate.takeUnless { legacyTypoHelp && !tuning.pageWordsBeforeTags }
        claimDocuments(substringLane, judge)
        claimDocuments(fuzzyLane, judge)
        val bm25Results = bm25Lane.asDocumentRows()
        val substringResults = substringLane.asDocumentRows()
        val fuzzyResults = fuzzyLane.asDocumentRows()

        var lexicalFusion: Fusion? = null
        if (onStage != null &&
            (bm25Results.isNotEmpty() || substringResults.isNotEmpty() || fuzzyResults.isNotEmpty())) {
            lexicalFusion = fuseAndRank(bm25Results, emptyList(), substringResults, fuzzyResults,
                emptyMap(), plan, qLower, lexicalGate, keepVectorOnly = false)
            surface(RetrievalStage.LEXICAL, lexicalFusion)
        }

        // The vector lane names pages by meaning, which the gate has no measure of: a document
        // a keyword lane has named keeps that lane's page, so the rows above stay as they are.
        val vectorLane = vectorDeferred.await()
        claimDocuments(vectorLane, judge = null)
        val vectorResults = vectorLane.asDocumentRows()

        VaultLog.d("HybridSearch",
            "q='${VaultLog.redact(q)}' bm25=${bm25Results.size} vec=${vectorResults.size} " +
            "sub=${substringResults.size} fuzzy=${fuzzyResults.size}")

        if (bm25Results.isEmpty() && vectorResults.isEmpty() &&
            substringResults.isEmpty() && fuzzyResults.isEmpty()) {
            return@coroutineScope RetrievalResult(emptyList())
        }

        // ── 5. Late embedding for semantic document queries ───────────────
        val semanticDocBoosts = lateDocumentBoosts(queryType, qLower, q, bm25Results, substringResults, tuning, this)

        // ── 6–8. Fuse, boost, gate, re-rank ───────────────────────────────
        // With no semantic signal the FINAL fusion has exactly the LEXICAL stage's inputs, so
        // the progressive path reuses that fusion instead of ranking the same lanes twice.
        val fusion = lexicalFusion?.takeIf { vectorResults.isEmpty() && semanticDocBoosts.isEmpty() }
            ?: fuseAndRank(bm25Results, vectorResults, substringResults, fuzzyResults,
                semanticDocBoosts, plan, qLower, lexicalGate, keepVectorOnly = tuning.semanticParentHits)

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
                notes = if (tuning.candidateBounded) listOf("candidate-bounded") else emptyList(),
            ).also(it::accept)
        }
        RetrievalResult(fusion.items, trace, similarSpellingsOnly = fusion.similarSpellingsOnly)
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
                // A document has no date metadata and its timestamp is when it was imported, so
                // "marksheet 2023" excluded a marksheet added this year. An item that itself
                // says the year or month typed — in its text or its name — belongs to it too.
                val typed = constraint.typedTerms.map { it to wholeWord(it) }
                val nameSaysIt = HashMap<String, Boolean>()
                fun saysIt(item: VaultItem): Boolean = typed.any { (term, pattern) ->
                    (item.ocrText.contains(term, ignoreCase = true) && pattern.containsMatchIn(item.ocrText)) ||
                        when {
                            item.title != null -> pattern.containsMatchIn(SearchableName.of(item))
                            !item.itemType.isDocument -> false
                            else -> nameSaysIt.getOrPut("$term\n${item.sourceFile}") {
                                pattern.containsMatchIn(SearchableName.of(item))
                            }
                        }
                }
                repository.allItemsSnapshot().forEach { item ->
                    if (item.id !in haveDate && item.timestamp in lo..hi) matchingIds.add(item.id)
                    else if (typed.isNotEmpty() && saysIt(item)) matchingIds.add(item.id)
                }
            } else if (constraint.type == "AMOUNT" && constraint.operator == ConstraintOperator.GREATER_THAN &&
                constraint.numericValue != null) {
                matchingIds.addAll(repository.amountGreaterThanIds(constraint.numericValue))
            } else {
                // Nothing here can evaluate this constraint, so it must not filter. Counting it
                // as "matches nothing" emptied every search whose plan carried one.
                continue
            }
            currentIds = if (currentIds == null) matchingIds else currentIds.apply { retainAll(matchingIds) }
            if (currentIds.isEmpty()) break
        }
        return currentIds
    }

    /**
     * Hydrates engine candidate ids and restores the engine's rank order. SQLite answers an
     * `id IN (…)` lookup in primary-key order, not in the order asked, so without this the
     * RRF rank of every BM25/vector candidate was its alphabetical id position rather than
     * its relevance rank. Gated by [RetrievalTuning.rankOrderHydration].
     */
    private suspend fun hydrateRanked(ids: List<String>, tuning: RetrievalTuning): List<VaultItem> {
        if (ids.isEmpty()) return emptyList()
        val rows = repository.getByIds(ids)
        if (!tuning.rankOrderHydration) return rows
        val byId = rows.associateBy { it.id }
        return ids.distinct().mapNotNull { byId[it] }
    }

    /**
     * Vector hits resolved to rows that exist, via each chunk's EXPLICIT ownership record: a
     * document chunk vector is its own Room row; an image chunk vector belongs to the image
     * row its parentId names. Hits under [minSimilarity] are dropped, and each row is kept
     * once, at the rank of its most similar chunk.
     */
    private suspend fun ownedSemanticHits(vector: FloatArray, k: Int, minSimilarity: Float): List<VaultItem> {
        val hits = semantic.searchHits(vector, k).filter { it.similarity >= minSimilarity }
        if (hits.isEmpty()) return emptyList()
        val rows = repository.getByIds(hits.flatMap { listOf(it.chunkId, it.parentId) }.distinct())
            .associateBy { it.id }
        return hits.mapNotNull { rows[it.chunkId] ?: rows[it.parentId] }.distinctBy { it.id }
    }

    private suspend fun blankStrict(
        strictIds: Set<String>, plan: QueryPlan?, only: ((VaultItem) -> Boolean)?,
    ): List<VaultItem> {
        var filtered = repository.allItemsSnapshot().filter { it.id in strictIds && (only == null || only(it)) }
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

    /**
     * A row as the scan lanes see it: its text and its searchable name ([SearchableName]),
     * both lowercased once upstream. [name] is empty for a row that has none.
     */
    private class Candidate(val item: VaultItem, row: RowText, val name: String) {
        val text: String = row.all
        /** Where the page ends in [text] and the tags begin. */
        val pageEnd: Int = row.pageEnd
    }

    /**
     * What a row has to be searched, lowered: its page and then its tags, as one string. The
     * page is the first [pageEnd] characters. Where it ends is known from the row — the page
     * and the tags are separate columns — so nothing is guessed from the text.
     */
    private class RowText(val all: String, val pageEnd: Int) {
        companion object {
            fun of(item: VaultItem): RowText {
                val page = item.ocrText.lowercase()
                if (item.tags.isEmpty()) return RowText(page, page.length)
                // A line break between them: no word runs from the page into the tags.
                return RowText(page + "\n" + item.tags.lowercase(), page.length)
            }
        }
    }

    private fun loweredName(item: VaultItem): String = SearchableName.of(item).lowercase()

    /** [term] standing as a word of its own: no letter, combining mark or digit on either side. */
    private fun wholeWord(term: String) = Regex(
        "(?<![\\p{L}\\p{M}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE
    )

    /** The document a row belongs to: its parent for a chunk row, itself for anything else. */
    private fun documentKey(item: VaultItem): String = item.parentDocumentId ?: item.id

    /** The first row of each document in this (ranked) order, up to [limit] documents. */
    private fun List<VaultItem>.firstPerDocument(limit: Int): List<VaultItem> {
        val seen = HashSet<String>()
        val out = ArrayList<VaultItem>(minOf(size, limit))
        for (item in this) {
            if (out.size >= limit) break
            if (seen.add(documentKey(item))) out.add(item)
        }
        return out
    }

    /** The scan lane's rows, and which of the query's words it found anywhere at all. */
    private class SubstringScan(val hits: List<VaultItem>, val wordsFound: Set<String>)

    /**
     * With [pageFirst], rows are ranked by how many of the query's words they say — on their
     * page or in their name, rather than among their tags — and only then by how many they
     * have counting the tags. So a document's first row here is one that says a word of the
     * query whenever any of its rows does.
     */
    private fun substringLane(
        items: List<Candidate>, qLower: String, limit: Int, onePerDocument: Boolean, pageFirst: Boolean,
    ): SubstringScan {
        if (qLower.length < 2) return SubstringScan(emptyList(), emptySet())
        val words = QueryWord.of(qLower)
        val found = BooleanArray(words.size)
        // Same result set and order as the previous filter → sortedByDescending → take:
        // any-word match to qualify, stable sort by match count descending, first [limit].
        val matched = ArrayList<Pair<VaultItem, Int>>()
        for (c in items) {
            var hits = 0
            var said = 0
            for (i in words.indices) {
                val inName = words[i].isIn(c.name)
                if (!inName && !words[i].isIn(c.text)) continue
                hits++; found[i] = true
                if (pageFirst && (inName || words[i].isIn(c.text, c.pageEnd))) said++
            }
            // One sort key: the count of words the row says, then the count it has at all.
            if (hits > 0) matched.add(c.item to said * SAID_RADIX + hits)
        }
        matched.sortByDescending { it.second } // stable, like sortedByDescending
        val ranked = matched.map { it.first }
        return SubstringScan(
            hits = if (onePerDocument) ranked.firstPerDocument(limit) else ranked.take(limit),
            wordsFound = words.indices.filter { found[it] }.mapTo(HashSet()) { words[it].typed },
        )
    }

    /**
     * [candidates] are the query words that may be matched by a near spelling; with [roots],
     * also by the word they were made from ([FuzzyMatcher]).
     */
    private fun fuzzyLane(
        items: List<Candidate>, qLower: String, candidates: List<String>, limit: Int, onePerDocument: Boolean,
        roots: Boolean, pageFirst: Boolean,
    ): List<VaultItem> {
        if (qLower.length < 4) return emptyList()
        val words = candidates.filter { it.length >= 3 }
        if (words.isEmpty()) return emptyList()
        // filter{...}.take(limit) ≡ first [limit] matches in list order — so stop scanning
        // once [limit] matches are found. The per-item predicate is [FuzzyMatcher]: the same
        // answer as the former split + full Levenshtein, without allocating per word.
        val matcher = FuzzyMatcher(words, roots)
        val out = ArrayList<VaultItem>(limit)
        // One row per document: a document that already matched is not scanned again.
        val matchedDocuments = if (onePerDocument) HashSet<String>() else null
        // With [pageFirst]: where in `out` a document stands by a row that has the near
        // spelling only among its tags, for a page of it that says the word to take over.
        val byTagOnly = if (onePerDocument && pageFirst) HashMap<String, Int>() else null
        for (c in items) {
            if (out.size >= limit) break
            val document = documentKey(c.item)
            if (matchedDocuments != null && document in matchedDocuments) continue
            val at = matcher.indexOfMatch(c.text)
            val saysIt = (at >= 0 && at < c.pageEnd) || (c.name.isNotEmpty() && matcher.matches(c.name))
            if (at < 0 && !saysIt) continue
            if (saysIt || byTagOnly == null) {
                val held = byTagOnly?.remove(document)
                if (held != null) out[held] = c.item else out.add(c.item)
                matchedDocuments?.add(document)
            } else if (document !in byTagOnly) {
                byTagOnly[document] = out.size
                out.add(c.item)
            }
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
        tuning: RetrievalTuning,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Map<String, Double> {
        if (queryType != QueryType.SEMANTIC_PHRASE || qLower.length < 6) return emptyMap()
        if (!tuning.semanticEnabled || !semantic.isReady) return emptyMap()
        val docCandidates = (bm25Results + substringResults)
            .filter { it.isDocumentPiece }
            .distinctBy { it.id }
            .take(VaultConfig.Retrieval.LATE_EMBED_DOC_LIMIT)
        if (docCandidates.isEmpty()) return emptyMap()
        val boosts = mutableMapOf<String, Double>()
        try {
            val queryVec = semantic.embedQuery(q, tuning.queryTrueLength)
            docCandidates.forEach { item ->
                scope.ensureActive()
                val chunkText = item.ocrText.trim()
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

    private class Fusion(
        val items: List<VaultItem>, val scores: Map<String, Double>,
        /** True when every row is there for a near spelling, none for a word of the query. */
        val similarSpellingsOnly: Boolean,
    )

    /**
     * Decides whether a fused row is a result, and what it earns for how its words match.
     *
     * [words] are the words a row is looked at for: the typed ones plus acronym expansions
     * ([ExpandedQuery.gateTerms]; for a query with no acronym, exactly the typed words).
     * [barePhrase] and [bareWords] are the query without the punctuation typed onto and into
     * its words.
     *
     * A row that has none of them stays, with [legacy], whenever a keyword lane named it —
     * the behaviour before typo help was limited to missing words. Without [legacy] it stays
     * only when [nearMissing] finds in it a near spelling of a typed word that is nowhere in
     * the vault as typed. Being named by the keyword engine is not enough for that: the engine
     * names a row for sharing a few three-letter pieces with the word, wherever in the row
     * they are, which is how a calendar came up for "brenda".
     *
     * With [pageFirst], what a row says — on its page, in its name — is told apart from its
     * tags ([RowText]); without it the tags are read as part of the page.
     */
    private class Gate(
        val words: List<QueryWord>,
        val barePhrase: String,
        val bareWords: Set<String>,
        val wordOrder: WordsInOrder?,
        val legacy: Boolean,
        val pageFirst: Boolean,
        val nearMissing: FuzzyMatcher?,
    ) {
        /** Where the page ends in a row's lowered text, for the checks below. */
        fun pageEnd(row: RowText): Int = if (pageFirst) row.pageEnd else row.all.length

        /** How many of the query's words are in a row with this text and name, tags included. */
        fun wordsIn(text: String, name: String): Int = words.count { it.isIn(text) || it.isIn(name) }

        /** How many of them the row says: on its page (which ends at [pageEnd]) or in its name. */
        fun wordsSaid(text: String, pageEnd: Int, name: String): Int =
            words.count { it.isIn(name) || it.isIn(text, pageEnd) }

        fun hasAPartOfAWord(text: String, name: String): Boolean =
            bareWords.any { text.contains(it) || name.contains(it) }

        fun hasANearSpelling(text: String, name: String): Boolean =
            nearMissing != null && (nearMissing.matches(text) || (name.isNotEmpty() && nearMissing.matches(name)))

        private fun saysANearSpelling(text: String, pageEnd: Int, name: String): Boolean =
            nearMissing != null && (nearMissing.indexOfMatch(text).let { it >= 0 && it < pageEnd } ||
                (name.isNotEmpty() && nearMissing.matches(name)))

        /**
         * What a row has to show for the query, most first: it says a word of it (4); it is
         * tagged with one (3); it says a near spelling of a word the vault does not have (2);
         * it is tagged with one (1); none of these (0).
         */
        fun standing(row: RowText, name: String): Int {
            val text = row.all
            val pageEnd = pageEnd(row)
            return when {
                wordsSaid(text, pageEnd, name) > 0 -> 4
                wordsIn(text, name) > 0 || hasAPartOfAWord(text, name) -> 3
                saysANearSpelling(text, pageEnd, name) -> 2
                hasANearSpelling(text, name) -> 1
                else -> 0
            }
        }
    }

    private suspend fun fuseAndRank(
        bm25Results: List<VaultItem>, vectorResults: List<VaultItem>,
        substringResults: List<VaultItem>, fuzzyResults: List<VaultItem>,
        semanticDocBoosts: Map<String, Double>, plan: QueryPlan?, qLower: String,
        gate: Gate,
        // When true, a vector-lane hit survives the presence gate with no query word in its
        // text: the lane has already held it to the similarity floor (semanticParentHits).
        keepVectorOnly: Boolean,
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

        // Text-presence gating. A query word counts when it is in the row's text or in its
        // searchable name; for a row with no name this is the prior logic unchanged.
        val bm25Ids = bm25Results.map { it.id }.toSet()
        val substringIds = substringResults.map { it.id }.toSet()
        val fuzzyIds = fuzzyResults.map { it.id }.toSet()
        val vectorIds = if (keepVectorOnly) vectorResults.map { it.id }.toSet() else emptySet()
        val queryWords = gate.words
        val barePhrase = gate.barePhrase.takeIf { it != qLower }
        var anyHasAQueryWord = false
        var anyByMeaning = false
        val toRemove = mutableListOf<String>()
        for ((id, item) in itemMap) {
            val row = RowText.of(item)
            val text = row.all
            val name = loweredName(item)
            val matched = gate.wordsIn(text, name)
            if (matched > 0) {
                anyHasAQueryWord = true
                // The bonuses are for the words the row says. One it is only tagged with keeps
                // it in the list, below the rows that say it.
                val pageEnd = gate.pageEnd(row)
                val page = if (pageEnd == text.length) text else text.substring(0, pageEnd)
                val said = if (pageEnd == text.length) matched else gate.wordsSaid(text, pageEnd, name)
                if (page.contains(qLower) || name.contains(qLower) ||
                    (barePhrase != null && (page.contains(barePhrase) || name.contains(barePhrase)))) {
                    rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.EXACT_PHRASE_BONUS
                }
                val ratio = said.toDouble() / queryWords.size
                rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.WORD_RATIO_BONUS * ratio
                gate.wordOrder?.let { order ->
                    val run = maxOf(order.longestRun(page), order.longestRun(name))
                    if (run >= 2) {
                        rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.WORDS_IN_ORDER_BONUS * (run - 1) / (order.size - 1)
                    }
                }
            } else {
                val stays = when {
                    id in vectorIds -> { anyByMeaning = true; true }
                    gate.legacy -> id in bm25Ids || id in substringIds || id in fuzzyIds
                    gate.hasAPartOfAWord(text, name) -> { anyHasAQueryWord = true; true }
                    // Whichever lane named it: the row itself has to have the near spelling.
                    else -> gate.hasANearSpelling(text, name)
                }
                if (!stays) toRemove.add(id)
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
        return Fusion(
            finalResults, rrfScores,
            similarSpellingsOnly = finalResults.isNotEmpty() && !anyHasAQueryWord && !anyByMeaning,
        )
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
}
