package com.amar.vault

import android.content.Context
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

/**
 * One row of the search result list: the card to show and, when a document was found by the
 * words on one of its pages, where in it — so the card can say so and a tap can open that page.
 */
data class SearchHit(
    val row: VaultItem,
    /**
     * The PDF page (1-based) the query was found on. Null when no page's text matched (the
     * document was found by its name) and for anything that is not a PDF.
     */
    val page: Int? = null,
    /** The words around the match, cut from that page's text. */
    val excerpt: String? = null,
    /**
     * Set when the item has none of the typed words and is listed for a word that is spelt
     * nearly the same: that word, as it stands in [excerpt]. The card marks it, so that it is
     * plain why the item is in the list.
     */
    val similarWord: String? = null,
    /**
     * Set when the item says neither the typed word nor anything like it, and is listed for a
     * tag the app gave it ("receipt" on a payment screenshot that says "Paid to"): that tag. The card
     * says so, where the excerpt would have been.
     */
    val filedUnder: String? = null,
    val saidInHindi: String? = null,
)

/**
 * The words of a typed query, as they are looked for in stored text: in the same canonical form
 * the text was stored in. A keyboard may send ज़ as one code point where the stored page has ज
 * followed by a nukta; compared raw, the word is on the page and is not found on it.
 */
internal fun queryWordsOf(query: String): List<String> =
    com.amar.vault.retrieval.QueryWord.of(UnicodeText.nfc(query).lowercase())
        // A word typed with punctuation on it ("claude?") is also the word without it.
        .flatMap { listOf(it.typed, it.bare) }
        .filter { it.length >= 2 }
        .distinct()

/** The few words around the place a query word appears in a stored page's text. */
internal object MatchExcerpt {
    private const val BEFORE = 40
    private const val AFTER = 110
    private val WHITESPACE = Regex("\\s+")

    /** Null when none of [queryWords] is in [page]. */
    fun of(page: String, queryWords: List<String>): String? {
        val text = page.replace(WHITESPACE, " ").trim()
        // Anchor on the longest word found: in "ministry of law" that is "ministry", not the
        // "of" that stands near the top of every page.
        val at = queryWords.sortedByDescending { it.length }
            .firstNotNullOfOrNull { word -> text.indexOf(word, ignoreCase = true).takeIf { it >= 0 && word.isNotBlank() } }
            ?: return null
        var start = (at - BEFORE).coerceAtLeast(0)
        var end = (at + AFTER).coerceAtMost(text.length)
        // Cut between words, not through one.
        if (start > 0) start = text.indexOf(' ', start).takeIf { it in 0 until at }?.plus(1) ?: start
        if (end < text.length) end = text.lastIndexOf(' ', end).takeIf { it > at } ?: end
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
    }

    /**
     * The first word of [page] that is spelt nearly like one of [queryWords] — what the
     * typo lanes matched it by. Null when there is none.
     */
    fun similarWord(page: String, queryWords: List<String>): String? {
        val words = queryWords.filter { it.length >= 3 }
        if (words.isEmpty()) return null
        val text = page.lowercase()
        return com.amar.vault.retrieval.FuzzyMatcher(words, roots = true).firstMatch(text)
            // As it stands on the page it may have a comma or a bracket on it.
            ?.let { com.amar.vault.retrieval.QueryWord(it).bare }?.takeIf { it.length >= 3 }
    }

    /**
     * The one of an item's [itemTags] that one of [queryWords] is found by — the word itself,
     * or else one spelt nearly like it. Null when it has no such tag.
     */
    fun tag(itemTags: String, queryWords: List<String>): String? {
        val tags = itemTags.lowercase().split(' ').filter { it.isNotBlank() }
        if (tags.isEmpty()) return null
        tags.firstOrNull { tag -> queryWords.any { tag.contains(it) } }?.let { return it }
        val words = queryWords.filter { it.length >= 3 }
        if (words.isEmpty()) return null
        return com.amar.vault.retrieval.FuzzyMatcher(words, roots = true).firstMatch(tags.joinToString(" "))
    }
}

/**
 * Turns matched rows into result cards, one per document: the first of a document's pages in
 * [rows]' order stands for it. A card carries the page and the words [queryWords] were found by.
 */
internal fun oneCardPerDocument(
    rows: List<VaultItem>,
    queryWords: List<String> = emptyList(),
    hindiSpellings: List<String> = emptyList(),
): List<SearchHit> {
    val shown = HashSet<String>()
    return rows.mapNotNull { row ->
        if (!shown.add(row.parentDocumentId ?: row.id)) return@mapNotNull null
        val inHindi = hindiSpellings.firstOrNull { ResultWording.containsAsWord(row.ocrText, it) }
            ?: hindiSpellings.firstOrNull { ResultWording.containsAsWord(com.amar.vault.retrieval.SearchableName.of(row), it) }
        val exact = MatchExcerpt.of(row.ocrText, queryWords) ?: inHindi?.let { MatchExcerpt.of(row.ocrText, listOf(it)) }
        // No typed word on the page, and none in the name either: it is here for a word spelt
        // nearly the same, or else for a tag.
        val unexplained = row.takeIf { exact == null }?.takeIf { item ->
            val name = com.amar.vault.retrieval.SearchableName.of(item).lowercase()
            queryWords.none { name.contains(it) }
        }
        val similar = unexplained?.let { MatchExcerpt.similarWord(it.ocrText, queryWords) }
        val excerpt = exact ?: similar?.let { MatchExcerpt.of(row.ocrText, listOf(it)) }
        SearchHit(
            row = row,
            page = row.pdfPage?.takeIf { excerpt != null },
            excerpt = excerpt?.let(com.amar.vault.vectors.Sensitive::mask),
            similarWord = similar,
            filedUnder = unexplained?.takeIf { similar == null }?.let { MatchExcerpt.tag(it.tags, queryWords) },
            saidInHindi = inHindi,
        )
    }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val db: VaultDatabase,
    private val retrievalService: com.amar.vault.retrieval.RetrievalService,
    private val photoSearch: com.amar.vault.photos.PhotoSearch,
    private val learned: com.amar.vault.vectors.AliasMemory,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    /**
     * The search screen opened: photo search starts loading its models now (on demand, not at
     * app start), so the photos for the first query are not held up by the load.
     */
    fun recordOpen(query: String, item: VaultItem) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { learned.opened(query, item.parentDocumentId ?: item.id, "OPENED") }
        }
    }

    fun warmUpPhotoSearch() = photoSearch.warmUp()

    // Entity Pages: read-only projection over the existing retrieval engine (no new storage/index).
    private val entityAggregator = EntityAggregator(retrievalService)

    /** Aggregates everything the vault already knows about [name] via the existing search engine. */
    suspend fun entityProfile(name: String): EntityProfile = entityAggregator.profile(name)

    // Events: read-only projection that groups an entity's references by time (reuses Entity Pages).
    private val eventAggregator = EventAggregator(entityAggregator)

    /** Groups the topic's existing references into time-clustered events. */
    suspend fun eventsForTopic(name: String): List<EventProjection> = eventAggregator.eventsForTopic(name)

    val query           = MutableStateFlow("")
    val queryPlan       = MutableStateFlow<QueryPlan?>(null)
    val isSearchLoading = MutableStateFlow(false)

    // Every write to the table reads all of it again, text and all, so only while a screen is
    // looking: left running, it went on for as long as the app was open once Photos had been seen,
    // over and over during a scan.
    val allItems: StateFlow<List<VaultItem>> = db.vaultDao()
        .getAllItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeFilter = MutableStateFlow(SearchFilter.ALL)
    val recentSearches = MutableStateFlow<List<String>>(emptyList())
    val quickFilters = SearchFilter.CHIPS

    /**
     * True while every result is in the list for a word spelt nearly like what was typed, and
     * none for the typed words themselves — the screen says so above the list.
     */
    val similarSpellingsOnly = MutableStateFlow(false)
    
    fun updateFilter(filter: String) {
        activeFilter.value = filter
    }
    
    fun recordSearch(q: String) {
        if (q.isNotBlank()) {
            com.amar.vault.search.core.HistoryManager.addSearch(q)
            recentSearches.value = com.amar.vault.search.core.HistoryManager.getRecentSearches()
        }
    }
    
    fun deleteRecentSearch(q: String) {
        com.amar.vault.search.core.HistoryManager.removeSearch(q)
        recentSearches.value = com.amar.vault.search.core.HistoryManager.getRecentSearches()
    }
    
    fun clearHistory() {
        com.amar.vault.search.core.HistoryManager.clearHistory()
        recentSearches.value = emptyList()
    }

    private val refreshRequests = MutableStateFlow(0)

    /**
     * What was read into the query in the box beyond words to look for — a period, an order,
     * a filter, what an abbreviation stands for — for the screen to show, each with a way to
     * take it back ([takeBack]).
     */
    val understood = MutableStateFlow<List<Understood>>(emptyList())

    /**
     * Said above the list when it is not what the query's reading asked for: the period left
     * nothing and the words were searched instead, or a filter hides every result.
     */
    val readingNote = MutableStateFlow<String?>(null)

    /** The keys of the readings the user has taken back for the query in the box. */
    private val readAsWords = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Pictures that look like the query in the box, most alike first: photo search's own list
     * ([com.amar.vault.retrieval.RetrievalResult.photos]), shown as a band above the result list.
     * They are found by what is in them, not by a word they say, and the band says so.
     */
    val photoMatches = MutableStateFlow<List<VaultItem>>(emptyList())

    val meaningMatches = MutableStateFlow<List<SearchHit>>(emptyList())

    val meaningFirst = MutableStateFlow(false)

    val meaningCoverage = MutableStateFlow(1f)

    /**
     * Takes one reading back. A filter that was written out (`type:pdf`) is taken out of the
     * box, there being nothing else it could mean; any other reading leaves the box as it is
     * and has its words looked for as words.
     */
    fun takeBack(reading: Understood) {
        if (reading.kind == Understood.Kind.FILTER) {
            updateQuery(query.value.replace(reading.typedAs, " ").replace(Regex("\\s+"), " ").trim())
        } else {
            readAsWords.update { it + reading.key }
        }
    }

    /**
     * Runs the query in the box again. The search screen calls this when it opens: the box keeps
     * its text between visits, and without this the list stayed as it was before whatever was
     * imported in between — "I indexed it, searched again, and it still isn't there".
     */
    fun refreshSearch() = refreshRequests.update { it + 1 }

    /** The result list with, for each document, the page and words the query was found on. */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchHits: StateFlow<List<SearchHit>> = combine(query, activeFilter, refreshRequests, readAsWords) { q, filter, refresh, asWords ->
        Triple(q, filter, refresh to asWords)
    }
        .debounce(250)
        .distinctUntilChanged()
        .transformLatest { (q, chip, more) ->
            val asWords = more.second
            if (q.isBlank()) recentSearches.value = com.amar.vault.search.core.HistoryManager.getRecentSearches()

            // Advanced-search operators: parsed additively. The cleaned free-text drives retrieval.
            val ops = SearchOperators.parse(q)
            val effectiveQuery = if (ops.hasAny) ops.cleanedQuery else q

            if (effectiveQuery.isBlank() && !ops.hasAny) {
                isSearchLoading.value = false
                understood.value = emptyList()
                readingNote.value = null
                photoMatches.value = emptyList()
            meaningMatches.value = emptyList()
                meaningMatches.value = emptyList()
                emit(emptyList())
                return@transformLatest
            }
            isSearchLoading.value = true
            readingNote.value = null
            photoMatches.value = emptyList()
            meaningMatches.value = emptyList()
            // True once any result list for this query is on screen — a later-stage failure must
            // not wipe results the user is already looking at.
            var painted = false
            try {
                yield()
                // SINGLE retrieval pipeline: RetrievalService → HybridSearchService (BM25 + OCR/substring +
                // fuzzy + vector + metadata boosts + temporal re-rank) — the same engine the agentic path
                // uses, so PDFs, OCR screenshots, images, shared links, notes and receipts all search
                // through one place.
                val request = com.amar.vault.retrieval.RetrievalRequest.forResultList(effectiveQuery, asWords)
                val meanings = AcronymDictionary.understoodIn(request.query, asWords)
                val read = request.plan?.understood.orEmpty()
                understood.value = ops.understood + read + meanings
                // A card shows the words it is listed for: the typed ones, and what an
                // abbreviation stands for when that is what the page says.
                val queryWords = queryWordsOf(effectiveQuery) +
                    AcronymDictionary.analyze(request.query, request.plainAbbreviations).meanings
                // The chip is asked inside the engine, before its caps: filtered afterwards, a
                // vault with more documents than the list is long would show no images at all.
                val only: ((VaultItem) -> Boolean)? = if (chip == SearchFilter.ALL) null else { item ->
                    SearchFilter.accepts(chip, item)
                }
                // Operators refine (narrow) the ranked results — never re-rank or bypass.
                // How many results the filters written into the query hide, when they hide all.
                var hiddenByFilters = 0
                suspend fun toRows(items: List<VaultItem>, hindi: List<String> = emptyList()): List<SearchHit> {
                    val kept = if (ops.hasAny) applyOperators(items, ops) else items
                    hiddenByFilters = if (kept.isEmpty() && items.isNotEmpty())
                        oneCardPerDocument(items, queryWords, hindi).size else 0
                    return oneCardPerDocument(kept, queryWords, hindi)
                }
                if (effectiveQuery.isBlank()) {
                    // Operator-only query (e.g. "type:pdf"): no free-text to rank — nothing for the
                    // retrieval engine to score, so browse the SAME Room source of truth the engine
                    // hydrates from (timestamp DESC) and let the operators narrow it. Not a second
                    // ranking path: any free text routes through retrievalService below.
                    similarSpellingsOnly.value = false
                    emit(toRows(db.vaultDao().getAll().let { all -> if (only != null) all.filter(only) else all }))
                    painted = true
                } else {
                    // Progressive retrieval: keyword hits paint as soon as the native BM25 lane
                    // returns; the substring/fuzzy and semantic stages then replace the list in
                    // place. The FINAL stage is the same ranking retrieve() returns.
                    learned.searched(effectiveQuery)
                    retrievalService.retrieveProgressive(request.copy(only = only)).collect { update ->
                        val rows = toRows(update.result.items, update.result.hindiSpellings)
                        val isFinal = update.stage == com.amar.vault.retrieval.RetrievalStage.FINAL
                        // An early stage that the operators narrow to nothing is not "no results"
                        // yet — keep the skeleton until a later stage or FINAL decides.
                        if (rows.isEmpty() && !isFinal) return@collect
                        // The engine is asked before its first stage; once the words say nothing,
                        // only the last stage can bring photos.
                        if (isFinal) {
                            // Photos come with the last stage only: the photo model is the slow part.
                            // Filters written into the query narrow them as they narrow the list.
                            val photoRows = update.result.photos.map { it.item }
                            photoMatches.value = if (ops.hasAny) applyOperators(photoRows, ops) else photoRows
                            val meaningRows = update.result.meaning?.shown?.map { it.item }.orEmpty()
                            val keptMeaning = if (ops.hasAny) applyOperators(meaningRows, ops) else meaningRows
                            meaningMatches.value = keptMeaning.map { row ->
                                SearchHit(row, page = row.pdfPage, excerpt = com.amar.vault.vectors.Sensitive.mask(row.ocrText.replace(Regex("\\s+"), " ").trim()).take(180))
                            }
                            meaningFirst.value = update.result.groups.firstOrNull()?.kind == com.amar.vault.retrieval.EvidenceKind.MEANING
                            meaningCoverage.value = update.result.meaning?.coverage ?: 1f
                            // A period or an amount that left nothing is no longer in force:
                            // its chip goes, and the screen says what is shown instead.
                            val dropped = if (update.result.filterDropped) read.filter { it.narrows } else emptyList()
                            understood.value = ops.understood + (read - dropped.toSet()) + meanings
                            readingNote.value = when {
                                dropped.isNotEmpty() -> ReadingWords.droppedBecauseEmpty(dropped)
                                hiddenByFilters > 0 -> ReadingWords.hiddenByFilters(hiddenByFilters, ops.understood)
                                else -> null
                            }
                        }
                        similarSpellingsOnly.value = update.result.similarSpellingsOnly
                        emit(rows)
                        painted = true
                        isSearchLoading.value = false
                    }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("SearchVM", "Search failed", e)
                if (!painted) emit(emptyList())
            }
            finally { isSearchLoading.value = false }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /** The same list as plain rows, for the screens that only draw the cards. */
    val results: StateFlow<List<VaultItem>> = searchHits
        .map { hits -> hits.map(SearchHit::row) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Post-filters existing ranked results by parsed operators. Item-field operators (type/ext,
     * before/after, has:ocr, source) filter on existing VaultItem fields; metadata operators
     * (entity, category) resolve to an id allow-set via the EXISTING metadata DAO. No ranking,
     * fusion, retrieval, or index is touched — this only narrows the already-ranked list.
     */
    private suspend fun applyOperators(
        items: List<VaultItem>,
        ops: ParsedOperators
    ): List<VaultItem> = withContext(Dispatchers.IO) {
        val metaDao = db.vaultMetadataDao()

        var allow: Set<String>? = null
        fun restrict(ids: Set<String>) { allow = allow?.let { it intersect ids } ?: ids }

        ops.entity?.let { raw ->
            val res = CanonicalEntityRegistry.resolve(raw)
            val display = if (res.decision == CanonicalizationDecision.RESOLVED)
                res.canonicalId?.let { CanonicalEntityRegistry.entities[it]?.displayName } else null
            val candidates = listOfNotNull(raw, raw.replaceFirstChar { it.uppercase() }, display).distinct()
            val ids = candidates.flatMap { v ->
                metaDao.getByTypeAndValue("ORGANIZATION", v).map { it.vaultItemId } +
                    metaDao.getByTypeAndValue("PAYMENT_APP", v).map { it.vaultItemId }
            }.toSet()
            restrict(ids)
        }
        ops.category?.let { raw ->
            val candidates = listOf(raw, raw.uppercase(), raw.replaceFirstChar { it.uppercase() }).distinct()
            val ids = candidates.flatMap { v ->
                metaDao.getByTypeAndValue("CATEGORY", v).map { it.vaultItemId } +
                    metaDao.getByTypeAndValue("DOCUMENT_CLASS", v).map { it.vaultItemId }
            }.toSet()
            restrict(ids)
        }

        val a = allow
        items.filter { item ->
            (ops.itemTypes.isEmpty() || item.itemType in ops.itemTypes) &&
                (ops.after == null || item.timestamp >= ops.after) &&
                (ops.before == null || item.timestamp < ops.before) &&
                (!ops.requireOcr || item.ocrText.isNotBlank()) &&
                (ops.source == null || sequenceOf(item.uri, item.sourceApp.orEmpty(), item.sourceFile)
                    .any { it.contains(ops.source, ignoreCase = true) }) &&
                (a == null || item.id in a)
        }
    }

    /**
     * Takes [row] out of the vault — the whole document when it is a page of one — and runs
     * the search again. [onDone] is told what went.
     */
    fun removeFromVault(row: VaultItem, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val removal = com.amar.vault.indexing.VaultRemoval(
                db = db,
                forgetKeywords = { ids ->
                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
                    ).bm25Index().removeDocuments(ids)
                },
                forgetFile = { hash -> com.amar.vault.indexing.PdfSourceReuseCache.forget(context, hash) },
                forgetVectors = { id -> photoSearch.forgetRows(listOf(id)) },
                letGo = { address ->
                    val uri = android.net.Uri.parse(address)
                    if (uri.scheme == "content") context.contentResolver.releasePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                },
                deleteCopy = { address -> com.amar.vault.indexing.SharedCopy.delete(context.filesDir, address) },
            )
            val removed = runCatching { removal.remove(row) }.onFailure { android.util.Log.e("SearchVM", "Could not remove ${row.id}", it) }.getOrNull()
            refreshSearch()
            withContext(Dispatchers.Main) { onDone(removed?.name) }
        }
    }

    suspend fun answerQuestion(queryText: String): VaultAnswer? = withContext(Dispatchers.IO) {
        val q = queryText.trim()
        if (q.isEmpty()) return@withContext null

        try {
            val routed = QueryRouter.route(q)
            // The question is read once, here, and the reading is kept. It used to be read
            // by the router, which took the date and order words out, and then again from
            // what was left — where there was no date or order to find. "Bills from last
            // month" was answered from a search for "bills from", over every month.
            //
            // An order word the vault says as part of a phrase ("last working day") is
            // one of the words asked about, not an order.
            val asWords = OrderWordsInPhrases.asWords(q) { phrase ->
                runCatching { db.vaultDao().anyTextMatches("\"$phrase*\"") }.getOrDefault(false)
            }
            val plan = QueryPlanner.parse(q, asWords = asWords)
            // Page-level hits: the answer's source passages are the best pages, which may
            // well be several pages of one document.
            // The pages are looked for by what the question is about. Looked for by every
            // word of it, the pages that say "is" and "the" most often came first.
            val about = DirectAnswers.aboutWords(plan.cleanedQuery).joinToString(" ").ifBlank { plan.cleanedQuery }
            val searchResults = retrievalService.retrieve(
                com.amar.vault.retrieval.RetrievalRequest(
                    about, plan,
                    tuning = com.amar.vault.retrieval.RetrievalTuning(onePerDocument = false),
                )
            ).items
            val direct = DirectAnswers.answer(plan.cleanedQuery, searchResults)
            // Nothing in the period asked about: say that, and what was read as the period.
            // An answer is not made up from other months.
            val nothingInPeriod = if (searchResults.isEmpty()) ReadingWords.nothingInPeriod(plan.understood) else null
            // The page the answer was read from is the first source.
            val sources = (listOfNotNull(searchResults.firstOrNull { it.id == direct?.sourceId }) + searchResults)
                .distinctBy { it.id }.take(3)

            val aggregator = KnowledgeAggregator(context)
            var kCard: KnowledgeCard? = null
            var cCard: CollectionCard? = null

            val resolution = CanonicalEntityRegistry.resolve(q)
            if (resolution.decision == CanonicalizationDecision.RESOLVED) {
                kCard = aggregator.buildCard(resolution.canonicalId!!)
            }

            val classConstraint = plan.preferred.find { it.type == "DOCUMENT_CLASS" }
            if (classConstraint != null) {
                try {
                    val docClass = DocumentClass.valueOf(classConstraint.value)
                    cCard = CollectionAggregator(context).buildCard(docClass)
                } catch (_: Exception) {}
            }

            // A question that asks for a sum over many items ("how much did I spend") is
            // not answered by one fact off one page.
            val overMany = Regex("(?i)\\b(total|spent|spend|expenses?|kharch|kharcha|sum)\\b").containsMatchIn(q)
            val answered = if (nothingInPeriod != null) nothingInPeriod
            else if (direct?.fact != null && !overMany) "⚡ ${direct.text}"
            else when (routed.tier) {
                QueryRouter.QueryTier.TIER0_REGEX -> "⚡ ${AnswerBuilders.fallback(q, searchResults, direct)}"
                QueryRouter.QueryTier.TIER1_SEARCH -> "🔍 ${AnswerBuilders.fallback(q, searchResults, direct)}"
                QueryRouter.QueryTier.TIER2_LLM -> AnswerBuilders.fallback(q, searchResults, direct)
            }

            // What was read into the question is said with the answer, with the way to
            // have the words taken as words instead.
            val finalAnswer = if (nothingInPeriod != null) answered
            else listOfNotNull(answered, ReadingWords.forAnAnswer(plan.understood)).joinToString("\n\n")

            VaultAnswer(finalAnswer, sources, kCard, cCard)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SearchVM", "Answer error: ${e.message}")
            null
        }
    }

    fun updateQuery(newQuery: String, plan: QueryPlan? = null) {
        queryPlan.value = plan
        // A reading that was taken back stays taken back while its words are still in the box.
        val lowered = newQuery.lowercase()
        readAsWords.update { keys -> keys.filterTo(HashSet()) { lowered.contains(it.substringAfter(':')) } }
        query.value = newQuery 
    }
}
