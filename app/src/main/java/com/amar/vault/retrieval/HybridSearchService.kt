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
import kotlinx.coroutines.withTimeoutOrNull

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
    private val boostConfig: BoostConfig,
    /** Pictures by what is in them ([RetrievalResult.photos]); [PhotoRetriever.NONE] lists none. */
    private val photos: PhotoRetriever = PhotoRetriever.NONE,
    private val meaning: MeaningRetriever = MeaningRetriever.NONE,
    private val learned: com.amar.vault.vectors.AliasMemory? = null,
    private val spellings: SpellingGuesser? = null,
) : RetrievalService {

    private companion object {
        /** Compiled once — the lanes previously re-compiled Regex("\\s+") per item/query. */
        val WHITESPACE = Regex("\\s+")
        /** Whatever is not part of a word: not a letter, a combining mark or a digit. */
        val NOT_A_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
        /** More than a query has words: the scan lane's sort key is `words said * this + words`. */
        const val SAID_RADIX = 1024
        const val TYPED_TERM_CANDIDATES = 2000
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
        val result = executeRelaxing(request) { stage, items, spellings ->
            val ms = elapsedMs()
            timings.append(stage.name.lowercase()).append('=').append(ms).append("ms ")
            emit(RetrievalUpdate(stage, RetrievalResult(items, hindiSpellings = spellings), ms))
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
        onStage: (suspend (RetrievalStage, List<VaultItem>, List<String>) -> Unit)?,
    ): RetrievalResult {
        val result = execute(request, onStage)
        val plan = request.plan
        // Photos from the period asked about are an answer too: the filter is kept for them.
        if (result.items.isNotEmpty() || result.photos.isNotEmpty() || !request.tuning.relaxEmptyFilter ||
            plan == null || plan.strict.isEmpty()) return result
        // With the words the filter was read from put back: for "last month" typed on its own
        // they are all there is to look for, and the list was simply empty.
        val words = request.wordsIfFilterEmpty ?: request.query
        if (words.isBlank()) return result
        VaultLog.d("HybridSearch", "filter matched nothing — searching the words without it")
        return execute(request.copy(query = words, plan = plan.copy(strict = emptyList())), onStage)
            .copy(filterDropped = true)
    }

    /**
     * The single retrieval path. [onStage] is null for [retrieve] (no intermediate fusion is
     * computed at all) and non-null for [retrieveProgressive]; it is only ever invoked from
     * this coroutine, never from inside a lane, so a `flow { }` collector may emit from it.
     */
    private suspend fun execute(
        request: RetrievalRequest,
        onStage: (suspend (RetrievalStage, List<VaultItem>, List<String>) -> Unit)?,
    ): RetrievalResult = coroutineScope {
        // Sprint 4A: NFC at the single query-side boundary, matching the NFC applied at
        // both ingestion boundaries — typed queries compare canonically against stored text.
        val q = com.amar.vault.UnicodeText.nfc(request.query)
        val plan = request.plan
        val budget = request.budget
        val tuning = request.tuning
        val sink = request.traceSink
        val qLower = q.lowercase().trim()

        // Sprint 4B.1: deterministic acronym expansion computed ONCE at THIS single query
        // boundary into an immutable [ExpandedQuery]. The original query (q/qLower) is
        // preserved verbatim for classification, length gates, and exact-phrase scoring;
        // only the recall-bearing inputs below consume the expansion. For a query without a
        // recognized acronym the expanded/joined forms equal the original, so normal/Hindi/
        // PDF/screenshot queries are unchanged (behaviour identical to Sprint 4B — this is a
        // representation change, not a behaviour change).
        val expanded = com.amar.vault.AcronymDictionary.analyze(q, request.plainAbbreviations)
        val expandedQuery = expanded.joined()                 // BM25 + semantic embed
        val expandedLower = expandedQuery.lowercase().trim()  // typo help for every word (legacy)
        // What an abbreviation stands for is looked for as a phrase. Its words one at a time
        // listed, for "otp", every page that says "one" or "time", with nothing on its card.
        val meanings = expanded.meanings.map(QueryWord::phrase)
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
        val bareWords = typedWords.flatMap { it.typed.split(NOT_A_WORD) }.filter { it.length >= 2 }.toSet()
        val wordOrder = typedWords.flatMap { it.typed.split(NOT_A_WORD) }.filter { it.isNotEmpty() }
            .takeIf { tuning.wordOrderBonus && it.size >= 2 }?.let(::WordsInOrder)

        val hinglishTyped = typedWords.distinctBy { it.typed }.map { it.bare }
        val hinglish = if (tuning.hinglishBridge && tuning.hinglishSaid) Hinglish.Query.of(hinglishTyped, null) else null
        val guesser = spellings.takeIf { tuning.hinglishModel && hinglish != null && !hinglish.isEmpty }

        val aliasTerms: List<String> = if (tuning.learning && learned != null) {
            runCatching { learned.expand(qLower) }.getOrDefault(emptyList()).flatMap { it.split(' ') }.distinct()
        } else emptyList()
        val openedBefore: Map<String, Float> = if (tuning.learning && learned != null) {
            runCatching { learned.openedBefore(qLower) }.getOrDefault(emptyMap())
        } else emptyMap()

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

        // ── Photos by what is in them: a list of their own, beside the lanes below ──
        // The period or amount narrows them inside the scan (by id), so the photos listed are
        // the best ones in the period, not the best ones overall that happen to fall in it.
        suspend fun photoHits(text: String): List<PhotoHit> {
            if (!tuning.photoLane || text.isBlank()) return emptyList()
            return try {
                val inPeriod: ((String) -> Boolean)? = strictIds?.let { ids -> { id: String -> id in ids } }
                val matches = photos.search(text, VaultConfig.Retrieval.PHOTO_BUDGET, inPeriod)
                if (matches.isEmpty()) return emptyList()
                val rows = repository.getByIds(matches.map { it.itemId }).associateBy { it.id }
                matches.mapNotNull { m -> rows[m.itemId]?.let { PhotoHit(it, m.score, m.margin) } }
                    .filter { only == null || only(it.item) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Like the other lanes: a failed photo search lists no photos, and the words still work.
                VaultLog.e("HybridSearch", "Photo lane error: ${e.message}"); emptyList()
            }
        }

        suspend fun meaningOf(text: String): MeaningResult? {
            if (!tuning.semanticEnabled || text.isBlank() || !meaning.isReady) return null
            return try {
                val inPeriod: ((String) -> Boolean)? = strictIds?.let { ids -> { id: String -> id in ids } }
                val scan = withTimeoutOrNull(VaultConfig.Retrieval.MEANING_DEADLINE_MS) { meaning.scan(text, budget.meaning, inPeriod) }
                when {
                    scan == null -> MeaningResult(emptyList(), emptyList(), Verdict.NOTHING, 0f, timedOut = true)
                    else -> MeaningLane.resolve(scan, repository, meaning, only, tuning.onePerDocument)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "Meaning lane error: ${e.message}"); null
            }
        }

        val photoDeferred = async(Dispatchers.Default) { if (qLower.isBlank()) emptyList() else photoHits(q) }
        val meaningDeferred = async(Dispatchers.Default) { if (qLower.isBlank()) null else meaningOf(q) }

        val factsDeferred = async(Dispatchers.IO) {
            if (!tuning.factsLane || qLower.isBlank()) return@async null
            try {
                FactsLane.find(q, repository, only?.let { o -> { row: VaultItem -> o(row) && (strictIds == null || row.id in strictIds) } }
                    ?: strictIds?.let { ids -> { row: VaultItem -> row.id in ids } }, tuning.onePerDocument)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "Facts lane error: ${e.message}"); null
            }
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
                // Just after the app is opened the engine is still being filled; the list
                // must not depend on how soon the query was typed.
                lexical.awaitReady()
                val folded = when {
                    hinglish != null -> hinglish.allKeys.filter { it !in qLower.split(WHITESPACE) }
                    tuning.hinglishBridge -> Hinglish.foldedQueryWords(qLower).filter { it !in qLower.split(WHITESPACE) }
                    else -> emptyList()
                }
                val extra = folded + aliasTerms
                val engineQuery = if (extra.isEmpty()) expandedQuery else expandedQuery + " " + extra.joinToString(" ")
                hydrateRanked(lexical.bm25(engineQuery, fetch), tuning).applyStrictFilter()
                    .let { if (tuning.onePerDocument) it.firstPerDocument(budget.bm25) else it }
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "BM25 error: ${e.message}"); emptyList()
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
                strictIds?.let { repository.inSnapshotOrder(it.toList()) } ?: boundedBaseSet(expandedQuery, budget, bm25Deferred.await())
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
            substringLane(loweredBaseDeferred.await(), qLower, meanings, budget.substring, tuning.onePerDocument,
                tuning.pageWordsBeforeTags, wholeWordsFirst = tuning.wholeWordsFirst)
        }
        // A typed word that is in the vault as typed needs no typo help: its look-alikes are
        // other words ("cloud" for "claude"), not misspellings of it. Null = the previous
        // behaviour, typo help for every word of the expanded query.
        val legacyTypoHelp = !tuning.typoHelpOnlyForMissingWords
        val readingDeferred = async(Dispatchers.Default) {
            if (guesser == null) return@async hinglish
            val found = substringDeferred.await().wordsFound
            val readsAsHinglish = typedWords.any { it.bare.length >= 3 && !Hinglish.isLittle(it.bare) && it.typed !in found } ||
                typedWords.any { Hinglish.isMarker(it.bare) }
            if (!readsAsHinglish) return@async hinglish
            try {
                Hinglish.Query(hinglishTyped, hinglishTyped.map { w -> ensureActive(); Hinglish.keysFor(w, guesser) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "Hinglish guesses error: ${e.message}"); hinglish
            }
        }
        val guessedLaneDeferred = async(Dispatchers.IO) {
            val reading = readingDeferred.await()
            if (reading == null || reading === hinglish) return@async null
            try {
                val known = hinglish?.allKeys.orEmpty().toSet()
                if (reading.allKeys.all { it in known }) return@async null
                val typed = qLower.split(WHITESPACE)
                val extra = reading.allKeys.filter { it !in typed } + aliasTerms
                val fetch = if (tuning.onePerDocument || only != null)
                    budget.bm25 * VaultConfig.Retrieval.BM25_DOC_OVERFETCH else budget.bm25
                lexical.awaitReady()
                hydrateRanked(lexical.bm25(expandedQuery + " " + extra.joinToString(" "), fetch), tuning).applyStrictFilter()
                    .let { if (tuning.onePerDocument) it.firstPerDocument(budget.bm25) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "Hinglish lane error: ${e.message}"); null
            }
        }
        val hindiQueryDeferred = async(Dispatchers.Default) {
            val reading = readingDeferred.await()
            if (guesser == null || !tuning.hinglishLanes || reading == null || reading === hinglish) return@async null
            val marked = typedWords.any { Hinglish.isMarker(it.bare) }
            val seenInHindi = marked || hinglishFound(reading, guessedLaneDeferred.await() ?: bm25Deferred.await())
                .any { it.length >= 3 && !Hinglish.isLittle(it) }
            if (!seenInHindi) return@async null
            try {
                val words = typedWords.map { w -> if (Hinglish.isLatinWord(w.bare)) guesser.hindi(w.bare) ?: w.bare else w.bare }
                words.joinToString(" ").takeIf { Hinglish.isDevanagari(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VaultLog.e("HybridSearch", "Hinglish spelling error: ${e.message}"); null
            }
        }
        val photoHindiDeferred = async(Dispatchers.Default) {
            hindiQueryDeferred.await()?.let { photoHits(it) }.orEmpty()
        }
        val missingWordsDeferred = async(Dispatchers.IO) {
            if (legacyTypoHelp) null
            else substringDeferred.await().wordsFound.let { found ->
                val inHindi = hinglishFound(readingDeferred.await(), guessedLaneDeferred.await() ?: bm25Deferred.await())
                typedWords.filter { it.typed !in found && it.bare !in inHindi }.map { it.bare }.distinct()
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
            onStage?.invoke(stage, fusion.items, fusion.spellings)
        }

        // What a row with none of the query's words in it may stay for (see [Gate]). The scan
        // decides which typed words are missing from the vault; until it has, the keyword
        // stage shows only rows that do have a query word, rather than flash look-alikes.
        val gateWords = typedWords.distinctBy { it.typed } + meanings
        fun gate(missingWords: List<String>, hindi: Hinglish.Query? = hinglish) = Gate(
            gateWords, barePhrase, bareWords, wordOrder, legacyTypoHelp, tuning.pageWordsBeforeTags,
            nameBonus = tuning.nameBonus,
            shadowWords = when {
                hindi != null -> hindi.allKeys.toSet()
                tuning.hinglishBridge -> Hinglish.foldedQueryWords(qLower).toSet()
                else -> emptySet()
            } + aliasTerms.map { Hinglish.fold(it) },
            aliasWords = aliasTerms.toSet(),
            hinglish = hindi,
            typedCount = gateWords.size - meanings.size,
            nearMissing = missingWords.filter { it.length >= 3 }.takeIf { it.isNotEmpty() }
                ?.let { FuzzyMatcher(it, roots = true) },
        )

        // One row per document: each lane picked its own best page, and fusion scores by row
        // id, so two lanes naming one document by different pages would count as two results.
        // Every lane therefore names a document by one page: the page the FIRST lane to find
        // it chose — keyword, substring, typo, then vector, the order they are awaited below.
        //
        // Unless that page has less to show for the query than the page a later keyword lane
        // names ([Gate.standing]). The engine indexes a page's tags and its document's name
        // with its text, and names a page that is only tagged with a word, or only belongs
        // to a file called by it, as readily as one that says it — sooner, if it is the
        // shorter page. Left as the document's row, that is a result with nothing on it to
        // show for the query. (Until the engine was rewritten it also named pages for
        // sharing three-letter pieces with a word it did not know: for "brenda", a page that
        // said "break", "current" and "standard". It now looks for the word inside longer
        // words and then for near spellings, as the lanes below do; this rule stays as the
        // judge of whatever it names.)
        // A page that says a query word is never swapped for another once it has been shown.
        val documentRow = HashMap<String, VaultItem>()
        val standings = HashMap<String, Int>()
        fun claimDocuments(lane: List<VaultItem>, judge: Gate?) {
            if (!tuning.onePerDocument) return
            fun standing(row: VaultItem) =
                standings.getOrPut(row.id) { judge!!.standing(RowText.of(row), loweredName(row), judge.hindiOf(row)) }
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
            surface(RetrievalStage.KEYWORD, fuseAndRank(bm25Lane.asDocumentRows(), emptyList(), emptyList(),
                plan, qLower, gate(emptyList()), openedBefore))
        }

        val substringLane = substringDeferred.await().hits
        val fuzzyLane = fuzzyDeferred.await()
        val lexicalGate = gate(missingWordsDeferred.await().orEmpty(), readingDeferred.await())
        // With typo help for every word and tags read as text, a document keeps the first page
        // named, as before.
        val judge = lexicalGate.takeUnless { legacyTypoHelp && !tuning.pageWordsBeforeTags }
        val guessedLane = guessedLaneDeferred.await()
        guessedLane?.let { claimDocuments(it, judge) }
        claimDocuments(substringLane, judge)
        claimDocuments(fuzzyLane, judge)
        val bm25Results = (guessedLane ?: bm25Lane).asDocumentRows()
        val substringResults = substringLane.asDocumentRows()
        val fuzzyResults = fuzzyLane.asDocumentRows()

        var lexicalFusion: Fusion? = null
        if (onStage != null &&
            (bm25Results.isNotEmpty() || substringResults.isNotEmpty() || fuzzyResults.isNotEmpty())) {
            lexicalFusion = fuseAndRank(bm25Results, substringResults, fuzzyResults, plan, qLower, lexicalGate, openedBefore)
            surface(RetrievalStage.LEXICAL, lexicalFusion)
        }

        VaultLog.d("HybridSearch",
            "q='${VaultLog.redact(q)}' bm25=${bm25Results.size} " +
            "sub=${substringResults.size} fuzzy=${fuzzyResults.size}")

        if (bm25Results.isEmpty() && substringResults.isEmpty() && fuzzyResults.isEmpty()) {
            val photosFound = mergedPhotos(photoDeferred.await(), photoHindiDeferred.await())
            val meaningFound = meaningDeferred.await()
            val factsFound = factsDeferred.await()
            val (groups, verdict) = Groups.of(emptyList(), 0, 0, false, meaningFound, photosFound, factsFound)
            return@coroutineScope RetrievalResult(emptyList(), photos = photosFound, meaning = meaningFound, facts = factsFound, groups = groups,
                verdict = verdict, keywordWords = typedWords.distinctBy { it.typed }.size)
        }

        // ── 6–8. Fuse, boost, gate, re-rank ───────────────────────────────
        val fusion = lexicalFusion
            ?: fuseAndRank(bm25Results, substringResults, fuzzyResults, plan, qLower, lexicalGate, openedBefore)

        val trace = sink?.let {
            RetrievalTrace(
                query = q,
                lanes = listOf(
                    LaneTrace("bm25", bm25Results.map { r -> r.id }),
                    LaneTrace("substring", substringResults.map { r -> r.id }),
                    LaneTrace("fuzzy", fuzzyResults.map { r -> r.id }),
                ),
                fusedScores = fusion.scores,
                finalOrder = fusion.items.map { r -> r.id },
                notes = if (tuning.candidateBounded) listOf("candidate-bounded") else emptyList(),
            ).also(it::accept)
        }
        val photosFound = mergedPhotos(photoDeferred.await(), photoHindiDeferred.await())
        val meaningFound = meaningDeferred.await()
        val distinctTyped = typedWords.distinctBy { it.typed }
            .let { all -> all.filter { !com.amar.vault.ResultWording.isSmall(it.bare) }.ifEmpty { all } }
        val said = fusion.items.firstOrNull()?.let { top ->
            val row = RowText.of(top)
            val name = loweredName(top)
            val pageEnd = lexicalGate.pageEnd(row)
            val hindi = lexicalGate.hindiOf(top)
            val typedIndex = typedWords.distinctBy { it.typed }.map { it.typed }
            distinctTyped.count { w ->
                w.isIn(name) || w.isIn(row.all, pageEnd) ||
                    (hindi != null && !Hinglish.isLittle(w.bare) && typedIndex.indexOf(w.typed).let { it >= 0 && hindi.said[it] })
            }
        } ?: 0
        val keywordVerdict = Groups.keywordVerdict(fusion.items, said, distinctTyped.size, fusion.similarSpellingsOnly)
        val factsFound = factsDeferred.await()
        val (groups, verdict) = Groups.of(fusion.items, said, distinctTyped.size, fusion.similarSpellingsOnly, meaningFound, photosFound, factsFound)
        RetrievalResult(fusion.items, trace, similarSpellingsOnly = fusion.similarSpellingsOnly,
            photos = photosFound, meaning = meaningFound, facts = factsFound, groups = groups, verdict = verdict,
            keywordWordsSaid = said, keywordWords = distinctTyped.size, hindiSpellings = fusion.spellings)
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
                matchingIds.addAll(repository.undatedItemIdsInRange(lo, hi))
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
                if (typed.isNotEmpty()) {
                    lexical.awaitReady()
                    val candidates = typed.flatMap { (term, _) -> lexical.bm25(term, TYPED_TERM_CANDIDATES) + lexical.fts(term, TYPED_TERM_CANDIDATES) }.distinct()
                    candidates.chunked(500).forEach { some ->
                        repository.getByIds(some).forEach { item -> if (item.id !in matchingIds && saysIt(item)) matchingIds.add(item.id) }
                    }
                }
            } else if (constraint.type == "AMOUNT" && constraint.amountRange != null) {
                matchingIds.addAll(repository.amountBetweenIds(constraint.amountRange.first, constraint.amountRange.second))
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

    private fun mergedPhotos(typed: List<PhotoHit>, hindi: List<PhotoHit>): List<PhotoHit> {
        if (hindi.isEmpty()) return typed
        val best = LinkedHashMap<String, PhotoHit>()
        for (hit in typed + hindi) {
            val kept = best[hit.item.id]
            if (kept == null || hit.score > kept.score) best[hit.item.id] = hit
        }
        return best.values.sortedByDescending { it.score }.take(VaultConfig.Retrieval.PHOTO_BUDGET)
    }

    private fun hinglishFound(hinglish: Hinglish.Query?, rows: List<VaultItem>): Set<String> {
        if (hinglish == null || hinglish.isEmpty) return emptySet()
        val found = HashSet<String>()
        for (row in rows) {
            val m = hinglish.matchRow(row.id, row.ocrText, SearchableName.of(row)) ?: continue
            for (i in hinglish.typed.indices) if (m.said[i]) found += hinglish.typed[i]
        }
        return found
    }

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

    /** A row the scan lane matched: its sort key, and how many query words are on its page. */
    private class Matched(val item: VaultItem, val key: Int, val onPage: Int)

    /**
     * With [pageFirst], rows are ranked by how many of the query's words they say — on their
     * page or in their name, rather than among their tags — and only then by how many they
     * have counting the tags. So a document's first row here is one that says a word of the
     * query whenever any of its rows does.
     *
     * Every row of a document carries the document's name, so by a word of the name they all
     * rank alike. The document keeps the place of its first row, and is named by the row,
     * among those that rank with it, with the most of the query's words on the page itself
     * ([firstPerDocumentByItsPage]).
     */
    private fun substringLane(
        items: List<Candidate>, qLower: String, meanings: List<QueryWord>, limit: Int, onePerDocument: Boolean,
        pageFirst: Boolean, wholeWordsFirst: Boolean = false,
    ): SubstringScan {
        if (qLower.length < 2) return SubstringScan(emptyList(), emptySet())
        val words = QueryWord.of(qLower) + meanings
        val found = BooleanArray(words.size)
        // Same result set and order as the previous filter → sortedByDescending → take:
        // any-word match to qualify, stable sort by match count descending, first [limit].
        val matched = ArrayList<Matched>()
        for (c in items) {
            var hits = 0
            var said = 0
            var onPage = 0
            var alone = 0
            for (i in words.indices) {
                val inName = words[i].isIn(c.name)
                if (!inName && !words[i].isIn(c.text)) continue
                hits++; found[i] = true
                if (!pageFirst) continue
                val onThePage = words[i].isIn(c.text, c.pageEnd)
                if (onThePage) onPage++
                if (inName || onThePage) said++
                if (wholeWordsFirst && (words[i].standsAloneIn(c.text, c.pageEnd) || words[i].standsAloneIn(c.name))) alone++
            }
            // One sort key: the count of words the row says, then the count it has at all, then
            // how many of them stand as words of their own. Rows that tie on all of that keep
            // the order the vault hands them over in, newest first.
            if (hits > 0) matched.add(Matched(c.item, (said * SAID_RADIX + hits) * SAID_RADIX + alone, onPage))
        }
        matched.sortByDescending { it.key } // stable, like sortedByDescending
        return SubstringScan(
            hits = when {
                !onePerDocument -> matched.take(limit).map { it.item }
                pageFirst -> firstPerDocumentByItsPage(matched, limit)
                else -> matched.map { it.item }.firstPerDocument(limit)
            },
            wordsFound = words.indices.filter { found[it] }.mapTo(HashSet()) { words[it].typed },
        )
    }

    /**
     * The first [limit] documents of [ranked], in the order of their first rows, each named by
     * the row with the most query words on its page among its rows that rank with its first.
     */
    private fun firstPerDocumentByItsPage(ranked: List<Matched>, limit: Int): List<VaultItem> {
        val place = HashMap<String, Int>()
        val out = ArrayList<Matched>(minOf(ranked.size, limit))
        for (row in ranked) {
            val document = documentKey(row.item)
            val at = place[document]
            if (at == null) {
                if (out.size < limit) {
                    place[document] = out.size
                    out.add(row)
                }
            } else if (row.key == out[at].key && row.onPage > out[at].onPage) {
                out[at] = row
            }
        }
        return out.map { it.item }
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
        val words = q.lowercase().split(WHITESPACE).filter { it.length >= 2 && !com.amar.vault.ResultWording.isSmall(it) }.distinct()
        val perWord = if (words.size < 2) emptyList() else words.flatMap { lexical.bm25(it, budget.bm25) }
        val unionIds = (bm25Hydrated.map { it.id } + ftsIds + perWord).distinct()
        val union = repository.inSnapshotOrder(unionIds)
        return if (union.isEmpty()) repository.allItemsSnapshot() else union
    }

    // ════════════════════════════════════════════════════════════════════════
    // Fusion + boosts + gating + temporal re-rank
    // ════════════════════════════════════════════════════════════════════════

    private class Fusion(
        val items: List<VaultItem>, val scores: Map<String, Double>,
        /** True when every row is there for a near spelling, none for a word of the query. */
        val similarSpellingsOnly: Boolean,
        val spellings: List<String> = emptyList(),
    )

    /**
     * Decides whether a fused row is a result, and what it earns for how its words match.
     *
     * [words] are what a row is looked at for: the typed words and, for an abbreviation, what
     * it stands for as a phrase ([QueryWord.phrase]).
     * [barePhrase] and [bareWords] are the query without the punctuation typed onto and into
     * its words.
     *
     * A row that has none of them stays, with [legacy], whenever a keyword lane named it —
     * the behaviour before typo help was limited to missing words. Without [legacy] it stays
     * only when [nearMissing] finds in it a near spelling of a typed word that is nowhere in
     * the vault as typed. Being named by the keyword engine is not enough for that: what the
     * engine names is a candidate, and the row itself has to show the word or the near
     * spelling. (The engine once named a row for sharing a few three-letter pieces with the
     * word, wherever in the row they were, which is how a calendar came up for "brenda".)
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
        val nameBonus: Boolean = true,
        val shadowWords: Set<String> = emptySet(),
        val aliasWords: Set<String> = emptySet(),
        val hinglish: Hinglish.Query? = null,
        val typedCount: Int = words.size,
    ) {
        fun hindiOf(item: VaultItem): Hinglish.Match? {
            val q = hinglish ?: return null
            if (q.isEmpty) return null
            return q.matchRow(item.id, item.ocrText, SearchableName.of(item))
        }

        private fun saidInHindi(hindi: Hinglish.Match?, i: Int): Boolean =
            hindi != null && i < typedCount && i < hindi.said.size && hindi.said[i]

        fun saysAnAlias(text: String, name: String): Boolean =
            aliasWords.any { w -> QueryWord.of(w).firstOrNull()?.let { it.isIn(text) || it.isIn(name) } == true }

        fun hasAShadow(item: VaultItem): Boolean {
            if (shadowWords.isEmpty() || !Hinglish.isDevanagari(item.ocrText + item.sourceFile)) return false
            val shadows = Hinglish.shadows(item.ocrText + " " + item.sourceFile).split(' ')
            return shadows.any { it in shadowWords }
        }

        /** Where the page ends in a row's lowered text, for the checks below. */
        fun pageEnd(row: RowText): Int = if (pageFirst) row.pageEnd else row.all.length

        /** How many of the query's words are in a row with this text and name, tags included. */
        fun wordsIn(text: String, name: String, hindi: Hinglish.Match? = null): Int =
            words.indices.count { i -> words[i].isIn(text) || words[i].isIn(name) || saidInHindi(hindi, i) }

        /** How many of them the row says: on its page (which ends at [pageEnd]) or in its name. */
        fun wordsSaid(text: String, pageEnd: Int, name: String, hindi: Hinglish.Match? = null): Int =
            words.indices.count { i -> words[i].isIn(name) || words[i].isIn(text, pageEnd) || saidInHindi(hindi, i) }

        /** Whether the page itself says one of them, whatever the row is called. */
        fun pageSaysAWord(text: String, pageEnd: Int): Boolean = words.any { it.isIn(text, pageEnd) }

        fun hasAPartOfAWord(text: String, name: String): Boolean =
            bareWords.any { text.contains(it) || name.contains(it) }

        fun hasANearSpelling(text: String, name: String): Boolean =
            nearMissing != null && (nearMissing.matches(text) || (name.isNotEmpty() && nearMissing.matches(name)))

        private fun saysANearSpelling(text: String, pageEnd: Int, name: String): Boolean =
            nearMissing != null && (nearMissing.indexOfMatch(text).let { it >= 0 && it < pageEnd } ||
                (name.isNotEmpty() && nearMissing.matches(name)))

        /**
         * What a row has to show for the query, most first: its page says a word of it (5);
         * only its name does, which every row of a document shares (4); it is tagged with one
         * (3); it says a near spelling of a word the vault does not have (2); it is tagged
         * with one (1); none of these (0).
         */
        fun standing(row: RowText, name: String, hindi: Hinglish.Match? = null): Int {
            val text = row.all
            val pageEnd = pageEnd(row)
            return when {
                pageFirst && (pageSaysAWord(text, pageEnd) || hindi?.onPage == true) -> 5
                wordsSaid(text, pageEnd, name, hindi) > 0 -> 4
                wordsIn(text, name) > 0 || hasAPartOfAWord(text, name) -> 3
                saysANearSpelling(text, pageEnd, name) -> 2
                hasANearSpelling(text, name) -> 1
                else -> 0
            }
        }
    }

    private suspend fun fuseAndRank(
        bm25Results: List<VaultItem>,
        substringResults: List<VaultItem>, fuzzyResults: List<VaultItem>,
        plan: QueryPlan?, qLower: String,
        gate: Gate,
        openedBefore: Map<String, Float> = emptyMap(),
    ): Fusion {
        val cfg = VaultConfig.Retrieval
        val weights = mapOf(
            "bm25" to cfg.WEIGHT_BM25,
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
        scoreLane(bm25Results, "bm25")
        scoreLane(substringResults, "substring"); scoreLane(fuzzyResults, "fuzzy")

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
        val queryWords = gate.words
        val barePhrase = gate.barePhrase.takeIf { it != qLower }
        var anyHasAQueryWord = false
        val toRemove = mutableListOf<String>()
        for ((id, item) in itemMap) {
            val row = RowText.of(item)
            val text = row.all
            val name = loweredName(item)
            val hindi = gate.hindiOf(item)
            val matched = gate.wordsIn(text, name, hindi)
            if (matched > 0) {
                anyHasAQueryWord = true
                // The bonuses are for the words the row says. One it is only tagged with keeps
                // it in the list, below the rows that say it.
                val pageEnd = gate.pageEnd(row)
                val page = if (pageEnd == text.length) text else text.substring(0, pageEnd)
                val said = if (pageEnd == text.length) matched else gate.wordsSaid(text, pageEnd, name, hindi)
                if (page.contains(qLower) || name.contains(qLower) ||
                    (barePhrase != null && (page.contains(barePhrase) || name.contains(barePhrase))) ||
                    (hindi != null && hindi.run >= gate.typedCount)) {
                    rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.EXACT_PHRASE_BONUS
                }
                val ratio = said.toDouble() / queryWords.size
                rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.WORD_RATIO_BONUS * ratio
                // A word in what the item is called is worth more than one somewhere on a page.
                if (gate.nameBonus && name.isNotEmpty()) {
                    val inName = queryWords.indices.count { i ->
                        queryWords[i].isIn(name) || (hindi != null && i < gate.typedCount && hindi.inName[i])
                    }
                    rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.NAME_BONUS * inName / queryWords.size
                }
                gate.wordOrder?.let { order ->
                    val run = maxOf(order.longestRun(page), order.longestRun(name), minOf(hindi?.run ?: 0, order.size))
                    if (run >= 2) {
                        rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.WORDS_IN_ORDER_BONUS * (run - 1) / (order.size - 1)
                    }
                }
            } else {
                val stays = when {
                    gate.legacy -> id in bm25Ids || id in substringIds || id in fuzzyIds
                    gate.hasAPartOfAWord(text, name) -> { anyHasAQueryWord = true; true }
                    gate.hasAShadow(item) -> true
                    gate.saysAnAlias(text, name) -> true
                    else -> gate.hasANearSpelling(text, name)
                }
                if (!stays) toRemove.add(id)
            }
        }
        toRemove.forEach { itemMap.remove(it); rrfScores.remove(it) }
        if (openedBefore.isNotEmpty()) for ((id, item) in itemMap) {
            val w = openedBefore[item.parentDocumentId ?: item.id] ?: continue
            rrfScores[id] = (rrfScores[id] ?: 0.0) + cfg.OPENED_BONUS * w
        }

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
            similarSpellingsOnly = finalResults.isNotEmpty() && !anyHasAQueryWord,
            spellings = finalResults.flatMap { gate.hindiOf(it)?.spellings.orEmpty() }.distinct(),
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
}
