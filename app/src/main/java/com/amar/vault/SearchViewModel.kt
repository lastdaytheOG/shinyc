package com.amar.vault

import android.content.Context
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import com.amar.vault.pipeline.stages.RepositoryMapper
import com.amar.vault.ui.renderengine.core.RichSavedItemBuilder
import com.amar.vault.ui.renderengine.models.RichSavedItem

/**
 * One row of the search result list: the card to show and, when a document was found by the
 * words on one of its pages, where in it — so the card can say so and a tap can open that page.
 */
data class SearchHit(
    val row: StashItemWithVaultItem,
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
 * [rows]' order stands for it, shown as the Saved item the document belongs to when it has one.
 * A shared PDF found by the words on one of its pages is then the PDF the user saved, with its
 * folder, note and favourite — not a separate, nameless page.
 *
 * [matched] are the items [rows] were built from; they carry each row's owning document, and
 * the page and text that [queryWords] are looked for in.
 */
internal fun oneCardPerDocument(
    rows: List<StashItemWithVaultItem>,
    matched: List<VaultItem>,
    savedByVaultId: Map<String, StashItemWithVaultItem>,
    queryWords: List<String> = emptyList(),
): List<SearchHit> {
    val matchedById = matched.associateBy { it.id }
    val shown = HashSet<String>()
    return rows.mapNotNull { row ->
        val found = matchedById[row.vaultItemId]
        val document = found?.parentDocumentId
        if (!shown.add(document ?: row.vaultItemId)) return@mapNotNull null
        val exact = found?.let { MatchExcerpt.of(it.ocrText, queryWords) }
        // No typed word on the page, and none in the name either: it is here for a word spelt
        // nearly the same, or else for a tag.
        val unexplained = found?.takeIf { exact == null }?.takeIf { item ->
            val name = com.amar.vault.retrieval.SearchableName.of(item).lowercase()
            queryWords.none { name.contains(it) }
        }
        val similar = unexplained?.let { MatchExcerpt.similarWord(it.ocrText, queryWords) }
        val excerpt = exact ?: if (found != null && similar != null) MatchExcerpt.of(found.ocrText, listOf(similar)) else null
        SearchHit(
            row = document?.let(savedByVaultId::get) ?: row,
            page = found?.pdfPage?.takeIf { excerpt != null },
            excerpt = excerpt,
            similarWord = similar,
            filedUnder = unexplained?.takeIf { similar == null }?.let { MatchExcerpt.tag(it.tags, queryWords) },
        )
    }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val db: VaultDatabase,
    private val vectorSearchManager: VectorSearchManager,
    private val retrievalService: com.amar.vault.retrieval.RetrievalService,
    private val ragService: RagService,
    private val languageModel: com.amar.vault.retrieval.LanguageModel,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    /** UI gateway to the single LLM owner — cancels any in-flight generation. */
    fun cancelGeneration() = languageModel.cancelGeneration()

    // Entity Pages: read-only projection over the existing retrieval engine (no new storage/index).
    private val entityAggregator = EntityAggregator(retrievalService)

    /** Aggregates everything the vault already knows about [name] via the existing search engine. */
    suspend fun entityProfile(name: String): EntityProfile = entityAggregator.profile(name)

    // Events: read-only projection that groups an entity's references by time (reuses Entity Pages).
    private val eventAggregator = EventAggregator(entityAggregator)

    /** Groups the topic's existing references into time-clustered events. */
    suspend fun eventsForTopic(name: String): List<EventProjection> = eventAggregator.eventsForTopic(name)

    /** Read-only single-document summary via the existing local RAG/LLM path. */
    suspend fun summarizeDocument(item: VaultItem): String = ragService.summarizeDocument(item)

    /**
     * Grounded single-document chat. Reuses [RagService.executeRag] with ONLY [item] as the source
     * (no cross-document retrieval), threading a short recent-conversation preamble into the query so
     * follow-ups stay contextual — all through the existing execution/prompt/failure path.
     */
    suspend fun chatWithDocument(
        item: VaultItem,
        history: List<ChatMessage>,
        question: String
    ): String {
        val recent = history.takeLast(6) // ~3 prior exchanges — keep small models within context
        val composed = if (recent.isEmpty()) {
            question
        } else buildString {
            append("Conversation so far:\n")
            recent.forEach { m -> append(if (m.isUser) "User: " else "Assistant: ").append(m.text).append('\n') }
            append("\nCurrent question: ").append(question)
        }
        return ragService.executeRag(composed, listOf(item))
    }

    val query           = MutableStateFlow("")
    val queryPlan       = MutableStateFlow<QueryPlan?>(null)
    val isSearchLoading = MutableStateFlow(false)
    val showFasterAiBanner = MutableStateFlow(false)

    fun dismissFasterAiBanner() {
        showFasterAiBanner.value = false
    }

    private val chatDao = db.chatMessageDao()
    private val modelDao = db.localModelDao()
    private val gson = Gson()

    val allItems: StateFlow<List<VaultItem>> = db.vaultDao()
        .getAllItems()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val stashItems: StateFlow<List<StashItemWithVaultItem>> = db.stashItemDao()
        .getStashItemsByType("SAVED")
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Derived facts (resolution/pages/size) are stable per file — cache so we don't
    // re-inspect files on every DB emission of the Saved list.
    private val derivedFactsCache =
        java.util.concurrent.ConcurrentHashMap<String, com.amar.vault.ui.renderengine.models.SavedDerivedFacts>()

    /** True until the Saved feed has resolved for the first time (drives the skeleton). */
    val isSavedLoading = MutableStateFlow(true)

    // Batched metadata query + cached file-fact derivation, shared by the Saved and
    // Archive feeds so both avoid the old N+1 storm.
    private suspend fun toRichItems(items: List<StashItemWithVaultItem>): List<RichSavedItem> {
        if (items.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val metaByItem = db.vaultMetadataDao()
                .getByItemIds(items.map { it.vaultItemId })
                .groupBy { it.vaultItemId }
            items.map { item ->
                val universalMetadata = RepositoryMapper.fromVaultMetadata(
                    metaByItem[item.vaultItemId] ?: emptyList()
                )
                val derived = derivedFactsCache.getOrPut(item.vaultItemId) {
                    com.amar.vault.ui.renderengine.core.SavedFactDeriver.derive(context, item)
                }
                RichSavedItemBuilder.build(item, universalMetadata, derived)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val richStashItems: StateFlow<List<RichSavedItem>> = stashItems
        .mapLatest { toRichItems(it) }
        .onEach { if (isSavedLoading.value) isSavedLoading.value = false }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ── Archive feed (reuses vaultType = "ARCHIVED") ──────────────────────────
    @OptIn(ExperimentalCoroutinesApi::class)
    val archivedRichItems: StateFlow<List<RichSavedItem>> = db.stashItemDao()
        .getStashItemsByType("ARCHIVED")
        .mapLatest { toRichItems(it) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val archivedCountFlow: StateFlow<Int> = db.stashItemDao()
        .getArchivedCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    // ── Saved organizational preferences (order/pins/recent/sort) ─────────────
    val savedPrefs: StateFlow<SavedPreferences.Prefs> = SavedPreferences.prefsFlow(context)
        .stateIn(viewModelScope, SharingStarted.Lazily, SavedPreferences.Prefs())

    // ── Undo (in-memory snapshots; no soft-delete column needed) ──────────────
    private var pendingDeleteSnapshots: List<StashItem> = emptyList()
    private var pendingArchiveIds: List<String> = emptyList()
    val savedUndo = MutableStateFlow<SavedUndo?>(null)

    val categoryCountsFlow: StateFlow<List<CategoryCount>> = db.stashItemDao()
        .getCategoriesWithCounts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Album-style category tiles (cover / count / last-updated), computed in-memory
    // from the already-loaded feed — no extra queries, no schema change.
    val categorySummaries: StateFlow<List<CategorySummary>> =
        combine(richStashItems, categoryCountsFlow, savedPrefs) { items, counts, prefs ->
            val byCat = items.groupBy { it.rawStashItem.category }
            val fromItems = counts.map { cc ->
                val group = byCat[cc.category].orEmpty()
                // Real item thumbnails feed the smart-preview collage (#5).
                val thumbs = group.mapNotNull { it.thumbnail }.take(4)
                CategorySummary(
                    category = cc.category,
                    count = cc.count,
                    lastUpdated = group.maxOfOrNull { it.savedAtMillis } ?: 0L,
                    coverThumbnail = thumbs.firstOrNull(),
                    previewThumbnails = thumbs
                )
            }
            // Surface user-created folders that have no items yet (#3), so a freshly
            // created empty folder still appears in the grid.
            val present = counts.map { it.category }.toSet()
            val emptyKnown = prefs.knownFolders
                .filter { it.isNotBlank() && it !in present }
                .map { name -> CategorySummary(category = name, count = 0, lastUpdated = 0L, coverThumbnail = null) }
            fromItems + emptyKnown
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Chat history Flow with source restoration
    val chatMessages: StateFlow<List<ChatMessage>> = chatDao
        .getMessagesForSession("default_session")
        .map { entities ->
            entities.map { entity ->
                // Decoupled from the VaultItem entity: decodes the versioned envelope and
                // tolerantly falls back to legacy raw-entity JSON. Never throws.
                val sources: List<VaultItem> = ChatSourceCodec.decode(entity.sourcesJson)
                val kCard: KnowledgeCard? = try {
                    if (!entity.knowledgeCardJson.isNullOrBlank()) {
                        gson.fromJson(entity.knowledgeCardJson, KnowledgeCard::class.java)
                    } else null
                } catch (e: Exception) {
                    null
                }
                val cCard: CollectionCard? = try {
                    if (!entity.collectionCardJson.isNullOrBlank()) {
                        gson.fromJson(entity.collectionCardJson, CollectionCard::class.java)
                    } else null
                } catch (e: Exception) {
                    null
                }
                ChatMessage(
                    text = entity.content,
                    isUser = entity.role == "user",
                    sources = sources,
                    knowledgeCard = kCard,
                    collectionCard = cCard
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (!vectorSearchManager.initialized) vectorSearchManager.initialize()
            android.util.Log.d("SearchVM", "VectorSearch ready: ${vectorSearchManager.getIndexedCount()} vectors")

            // Initialize models in DB if empty
            val existing = modelDao.getAllModels()
            if (existing.isEmpty()) {
                val destDir = File(context.filesDir, "models")
                val profile = DeviceCapability.getDeviceProfile(context)
                val recommendation = ModelRecommendationEngine.recommend(profile)
                val models = listOf(
                    LocalModel(
                        modelId = "qwen-3b",
                        displayName = "Qwen 2.5 3B Instruct (INT4)",
                        fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
                        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
                        sha256 = "cde75b28de5678ab12eef458a2309cf8b12f458e0a3cd84d9fde1f909fa00cde",
                        sizeBytes = 2200000000L,
                        requiredRamGb = 8.0f,
                        status = if (File(destDir, "qwen2.5-3b-instruct-q4_k_m.gguf").exists()) "READY" else "PENDING",
                        downloadProgress = 0,
                        isEnabled = (recommendation.modelId == "qwen-3b")
                    ),
                    LocalModel(
                        modelId = "qwen-1.5b",
                        displayName = "Qwen 2.5 1.5B Instruct (INT3)",
                        fileName = "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf",
                        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-IQ3_XXS.gguf",
                        sha256 = "faee12e8b2308faee230dfaef12a456ef128a30cf12ea45ef20aefdf1209ffde",
                        sizeBytes = 850000000L,
                        requiredRamGb = 4.0f,
                        status = if (File(destDir, "Qwen2.5-1.5B-Instruct.IQ3_XXS.gguf").exists()) "READY" else "PENDING",
                        downloadProgress = 0,
                        isEnabled = (recommendation.modelId == "qwen-1.5b")
                    ),
                    LocalModel(
                        modelId = "gemma-2b",
                        displayName = "Gemma 2 2B Instruct (INT4)",
                        fileName = "gemma-2-2b-it-q4_k_m.gguf",
                        downloadUrl = "https://huggingface.co/google/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-q4_k_m.gguf",
                        sha256 = "beef230f890daeffe890de890dfaef128a90cf12ea89ef90aefdf1209ffdebcad",
                        sizeBytes = 1600000000L,
                        requiredRamGb = 4.0f,
                        status = if (File(destDir, "gemma-2-2b-it-q4_k_m.gguf").exists()) "READY" else "PENDING",
                        downloadProgress = 0,
                        isEnabled = (recommendation.modelId == "gemma-2b")
                    )
                )
                modelDao.insertAll(models)
            }
        }
    }

    val activeFilter = MutableStateFlow(SearchFilter.ALL)
    val recentSearches = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions = MutableStateFlow<List<String>>(emptyList())
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
            // Update suggestions whenever query changes (if we wanted to do it instantly, we could put this in an onEach before debounce, but this is fine)
            if (q.isBlank()) {
                recentSearches.value = com.amar.vault.search.core.HistoryManager.getRecentSearches()
                searchSuggestions.value = emptyList()
            } else {
                searchSuggestions.value = com.amar.vault.search.core.SuggestionEngine.generateSuggestions(q, com.amar.vault.search.core.SearchIndexManager)
            }

            // Advanced-search operators: parsed additively. The cleaned free-text drives retrieval.
            val ops = SearchOperators.parse(q)
            val effectiveQuery = if (ops.hasAny) ops.cleanedQuery else q

            if (effectiveQuery.isBlank() && !ops.hasAny) {
                isSearchLoading.value = false
                understood.value = emptyList()
                readingNote.value = null
                emit(emptyList())
                return@transformLatest
            }
            isSearchLoading.value = true
            readingNote.value = null
            // True once any result list for this query is on screen — a later-stage failure must
            // not wipe results the user is already looking at.
            var painted = false
            try {
                yield()
                // SINGLE retrieval pipeline: RetrievalService → HybridSearchService (BM25 + OCR/substring +
                // fuzzy + vector + metadata boosts + temporal re-rank) — the same engine the agentic path
                // uses, so PDFs, OCR screenshots, images, saved links, notes and receipts all search through
                // one place. Retrieval returns VaultItems; reuse the real Saved row when the item is saved,
                // otherwise synthesize a wrapper (content-only items such as PDF/document chunks) so every
                // content type renders without losing any VaultItem information.
                //
                // Sourced from Room directly (not the lazily-shared StateFlow snapshots): the shared flows
                // only start when some screen collects them, so on screens that collect neither (e.g. the
                // search overlay) `.value` was permanently the initial emptyList — operator-only queries
                // returned nothing and saved-row reuse silently degraded to synthetic wrappers.
                val savedByVaultId = db.stashItemDao().getStashItemsByType("SAVED").first()
                    .associateBy { it.vaultItemId }
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
                    SearchFilter.accepts(chip, item, savedByVaultId[item.id] ?: item.parentDocumentId?.let(savedByVaultId::get))
                }
                // Operators refine (narrow) the ranked results — never re-rank or bypass.
                // How many results the filters written into the query hide, when they hide all.
                var hiddenByFilters = 0
                suspend fun toRows(items: List<VaultItem>): List<SearchHit> {
                    val base = items.map { vi -> savedByVaultId[vi.id] ?: vi.toStashWrapper() }
                    val kept = if (ops.hasAny) applyOperators(base, ops) else base
                    hiddenByFilters = if (kept.isEmpty() && base.isNotEmpty())
                        oneCardPerDocument(base, items, savedByVaultId, queryWords).size else 0
                    return oneCardPerDocument(kept, items, savedByVaultId, queryWords)
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
                    retrievalService.retrieveProgressive(request.copy(only = only)).collect { update ->
                        val rows = toRows(update.result.items)
                        val isFinal = update.stage == com.amar.vault.retrieval.RetrievalStage.FINAL
                        // An early stage that the operators narrow to nothing is not "no results"
                        // yet — keep the skeleton until a later stage or FINAL decides.
                        if (rows.isEmpty() && !isFinal) return@collect
                        if (isFinal) {
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
    val results: StateFlow<List<StashItemWithVaultItem>> = searchHits
        .map { hits -> hits.map(SearchHit::row) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Adapts a retrieval [VaultItem] into the UI's [StashItemWithVaultItem] shape for content-only
     * items that have no Saved row (e.g. PDF/document chunks). All VaultItem fields are carried over;
     * a stable synthetic stashId ("search_<id>") keeps LazyColumn keys unique and makes Saved-only
     * actions (favorite/delete) safe no-ops on non-saved results.
     */
    private fun VaultItem.toStashWrapper(): StashItemWithVaultItem = StashItemWithVaultItem(
        stashId = "search_$id",
        sessionId = null,
        vaultItemId = id,
        vaultType = "SAVED",
        category = "",
        savedAt = timestamp,
        sourceApp = sourceApp ?: "",
        isFavorite = false,
        createdAt = timestamp,
        userNote = null,
        thumbnailPath = null,
        uri = uri,
        ocrText = ocrText,
        itemType = itemType,
        sourceFile = sourceFile,
        timestamp = timestamp,
        title = title,
        mimeType = mimeType,
        tags = tags,
        parentDocumentId = parentDocumentId,
    )

    /**
     * Post-filters existing ranked results by parsed operators. Item-field operators (type/ext,
     * before/after, has:ocr, source) filter on existing VaultItem fields; metadata operators
     * (entity, category) resolve to an id allow-set via the EXISTING metadata DAO. No ranking,
     * fusion, retrieval, or index is touched — this only narrows the already-ranked list.
     */
    private suspend fun applyOperators(
        items: List<StashItemWithVaultItem>,
        ops: ParsedOperators
    ): List<StashItemWithVaultItem> = withContext(Dispatchers.IO) {
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
                (ops.source == null || sequenceOf(item.uri, item.sourceApp, item.sourceFile)
                    .any { it.contains(ops.source, ignoreCase = true) }) &&
                (a == null || item.vaultItemId in a)
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            chatDao.clearHistory()
        }
    }

    fun toggleSavedFavorite(stashId: String, isFavorite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().updateFavorite(stashId, isFavorite)
            if (isFavorite) SavedPreferences.recordFavorited(context, listOf(stashId))
        }
    }

    fun moveSavedCategory(stashId: String, category: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.stashItemDao().updateCategory(stashId, category) }
                .onSuccess { SavedPreferences.recordMoved(context, listOf(stashId)) }
                .onFailure { android.util.Log.w("SearchVM", "Move category failed", it) }
        }
    }

    fun updateNote(stashId: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().updateNote(stashId, note)
        }
    }

    fun updateThumbnailPath(stashId: String, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().updateThumbnailPath(stashId, path)
        }
    }

    /**
     * Takes [row] out of the vault — the whole document when it is a page of one — and runs
     * the search again. For a result that is not a Saved entry; [onDone] is told what went.
     */
    fun removeFromVault(row: StashItemWithVaultItem, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val removal = com.amar.vault.indexing.VaultRemoval(
                db = db,
                forgetKeywords = { ids ->
                    dagger.hilt.android.EntryPointAccessors.fromApplication(
                        context.applicationContext, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
                    ).bm25Index().removeDocuments(ids)
                },
                forgetFile = { hash -> com.amar.vault.indexing.PdfSourceReuseCache.forget(context, hash) },
                forgetVectors = { id -> vectorSearchManager.removeItem(id) },
                letGo = { address ->
                    val uri = android.net.Uri.parse(address)
                    if (uri.scheme == "content") context.contentResolver.releasePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                },
            )
            val removed = runCatching {
                if (row.parentDocumentId != null) removal.removeDocument(row.parentDocumentId)
                else removal.removeItem(row.vaultItemId)
            }.onFailure { android.util.Log.e("SearchVM", "Could not remove ${row.vaultItemId}", it) }.getOrNull()
            refreshSearch()
            withContext(Dispatchers.Main) { onDone(removed?.name) }
        }
    }

    fun deleteSavedItem(stashId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().deleteById(stashId)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 3 — Saved management (bulk ops, categories, archive, undo)
    // ══════════════════════════════════════════════════════════════════════════

    private fun snapshotsFor(ids: Collection<String>): List<StashItem> {
        val byId = (richStashItems.value + archivedRichItems.value).associateBy { it.id }
        return ids.mapNotNull { byId[it]?.rawStashItem?.toStashItem() }
    }

    fun bulkMove(ids: List<String>, category: String) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.stashItemDao().bulkSetCategory(ids, category) }
                .onSuccess { SavedPreferences.recordMoved(context, ids) }
                .onFailure { android.util.Log.w("SearchVM", "bulkMove failed", it) }
        }
    }

    fun bulkFavorite(ids: List<String>, favorite: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().bulkSetFavorite(ids, favorite)
            if (favorite) SavedPreferences.recordFavorited(context, ids)
        }
    }

    fun bulkArchive(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            pendingArchiveIds = ids.toList()
            runCatching { db.stashItemDao().bulkSetType(ids, "ARCHIVED") }
                .onFailure { android.util.Log.w("SearchVM", "archive failed", it) }
            savedUndo.value = SavedUndo(
                message = if (ids.size == 1) "1 item archived" else "${ids.size} items archived",
                kind = SavedUndo.Kind.ARCHIVE
            )
        }
    }

    fun bulkUnarchive(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { db.stashItemDao().bulkSetType(ids, "SAVED") }
    }

    fun bulkDelete(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            pendingDeleteSnapshots = snapshotsFor(ids)
            runCatching { db.stashItemDao().bulkDelete(ids) }
                .onFailure { android.util.Log.w("SearchVM", "bulkDelete failed", it) }
            savedUndo.value = SavedUndo(
                message = if (ids.size == 1) "1 item deleted" else "${ids.size} items deleted",
                kind = SavedUndo.Kind.DELETE
            )
        }
    }

    /** Re-applies the most recent destructive action's inverse. */
    fun performUndo() {
        val undo = savedUndo.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (undo.kind) {
                SavedUndo.Kind.DELETE -> {
                    pendingDeleteSnapshots.forEach { db.stashItemDao().insertOrUpdate(it) }
                    pendingDeleteSnapshots = emptyList()
                }
                SavedUndo.Kind.ARCHIVE -> {
                    if (pendingArchiveIds.isNotEmpty()) {
                        db.stashItemDao().bulkSetType(pendingArchiveIds, "SAVED")
                        pendingArchiveIds = emptyList()
                    }
                }
            }
            savedUndo.value = null
        }
    }

    fun clearUndo() {
        savedUndo.value = null
        pendingDeleteSnapshots = emptyList()
        pendingArchiveIds = emptyList()
    }

    // ── Category management ────────────────────────────────────────────────────

    fun renameCategory(oldName: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank() || clean == oldName) return
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().renameCategory(oldName, clean)
            SavedPreferences.onCategoryRenamed(context, oldName, clean)
        }
    }

    /** Merge = fold one category's items into another (rename into an existing name). */
    fun mergeCategory(from: String, into: String) {
        if (from == into) return
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().renameCategory(from, into)
            SavedPreferences.onCategoryRenamed(context, from, into)
        }
    }

    /** Delete a category by moving its items to Uncategorized (empty category). */
    fun deleteCategory(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.stashItemDao().renameCategory(name, "")
            SavedPreferences.onCategoryRemoved(context, name)
        }
    }

    fun togglePinCategory(name: String) {
        viewModelScope.launch(Dispatchers.IO) { SavedPreferences.togglePin(context, name) }
    }

    /** Create a folder with a chosen appearance (#3/#4). Persists to DataStore and
     * registers the name so an empty folder still surfaces in the grid. */
    fun createFolder(name: String, meta: FolderMeta) {
        val clean = name.trim()
        if (clean.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { SavedPreferences.saveFolderMeta(context, clean, meta) }
    }

    fun setCategoryOrder(order: List<String>) {
        viewModelScope.launch(Dispatchers.IO) { SavedPreferences.setCategoryOrder(context, order) }
    }

    // ── Sort + recently-opened ─────────────────────────────────────────────────

    fun setSavedSort(sort: SavedSortOption) {
        viewModelScope.launch(Dispatchers.IO) { SavedPreferences.setSort(context, sort) }
    }

    fun recordOpened(stashId: String) {
        viewModelScope.launch(Dispatchers.IO) { SavedPreferences.recordOpened(context, stashId) }
    }

    /** Related attachments = other items captured in the same share session. */
    suspend fun fetchRelated(item: RichSavedItem): List<RichSavedItem> {
        val sessionId = item.rawStashItem.sessionId ?: return emptyList()
        if (sessionId.isBlank()) return emptyList()
        val rows = withContext(Dispatchers.IO) {
            db.stashItemDao().getBySessionId(sessionId, item.id)
        }
        return toRichItems(rows)
    }

    fun sendAgenticQuery(queryText: String) {
        if (queryText.isBlank()) return
        val q = queryText.trim()

        viewModelScope.launch(Dispatchers.IO) {
            val sessionId = "default_session"

            // 1. Save User Message
            val userMsgId = UUID.randomUUID().toString()
            chatDao.insert(
                ChatMessageEntity(
                    messageId = userMsgId,
                    sessionId = sessionId,
                    role = "user",
                    content = q,
                    timestamp = System.currentTimeMillis()
                )
            )

            _isGenerating.value = true

            try {
                // 2. Classify and route query, then retrieve context
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
                // The answer read straight off those pages, when the question asks for
                // something written down: no model, and nothing to download.
                val direct = DirectAnswers.answer(plan.cleanedQuery, searchResults)
                // Nothing in the period asked about: say that, and what was read as the period.
                // An answer is not made up from other months, nor by the model from nothing.
                val nothingInPeriod = if (searchResults.isEmpty()) ReadingWords.nothingInPeriod(plan.understood) else null
                // The page the answer was read from is the first source.
                val sources = (listOfNotNull(searchResults.firstOrNull { it.id == direct?.sourceId }) + searchResults)
                    .distinctBy { it.id }.take(3)

                // 3. Resolve cards if applicable
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

                // 4. Invoke appropriate engine based on QueryRouter classification tier
                // A question that asks for a sum over many items ("how much did I spend") is
                // not answered by one fact off one page.
                val overMany = Regex("(?i)\\b(total|spent|spend|expenses?|kharch|kharcha|sum)\\b").containsMatchIn(q)
                val answered = if (nothingInPeriod != null) nothingInPeriod
                else if (direct?.fact != null && !overMany) "⚡ ${direct.text}"
                else when (routed.tier) {
                    QueryRouter.QueryTier.TIER0_REGEX -> {
                        val answer = buildFallbackAnswer(q, searchResults, direct)
                        "⚡ $answer"
                    }
                    QueryRouter.QueryTier.TIER1_SEARCH -> {
                        val answer = buildFallbackAnswer(q, searchResults, direct)
                        if (searchResults.isEmpty()) {
                            // No search results — let LLM handle it directly as direct chat fallback
                            val chatAnswer = ragService.executeRag(q, emptyList())
                            if (chatAnswer.isNotBlank()) "🧠 $chatAnswer" else "🔍 $answer"
                        } else {
                            "🔍 $answer"
                        }
                    }
                    QueryRouter.QueryTier.TIER2_LLM -> {
                        val ragAnswer = ragService.executeRag(q, sources)
                        if (ragAnswer.isNotBlank()) {
                            "🧠 $ragAnswer"
                        } else {
                            buildFallbackAnswer(q, searchResults, direct)
                        }
                    }
                }

                // What was read into the question is said with the answer, with the way to
                // have the words taken as words instead.
                val finalAnswer = if (nothingInPeriod != null) answered
                else listOfNotNull(answered, ReadingWords.forAnAnswer(plan.understood)).joinToString("\n\n")

                // Check degradation after potential generation
                val activeModel = modelDao.getEnabledModel()
                if (activeModel != null) {
                    val stats = RuntimeStatsManager(context)
                    if (stats.detectDegradation(activeModel.modelId)) {
                        showFasterAiBanner.value = true
                    }
                }

                // Serialize sources through the versioned DTO codec (schema-evolution safe)
                val sourcesJson = ChatSourceCodec.encode(sources)
                val kCardJson = kCard?.let { gson.toJson(it) }
                val cCardJson = cCard?.let { gson.toJson(it) }

                // 5. Save Assistant Message
                val assistantMsgId = UUID.randomUUID().toString()
                chatDao.insert(
                    ChatMessageEntity(
                        messageId = assistantMsgId,
                        sessionId = sessionId,
                        role = "assistant",
                        content = finalAnswer,
                        timestamp = System.currentTimeMillis(),
                        sourcesJson = sourcesJson,
                        knowledgeCardJson = kCardJson,
                        collectionCardJson = cCardJson
                    )
                )

            } catch (e: Exception) {
                android.util.Log.e("SearchVM", "RAG query error: ${e.message}")
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun updateQuery(newQuery: String, plan: QueryPlan? = null) {
        queryPlan.value = plan
        // A reading that was taken back stays taken back while its words are still in the box.
        val lowered = newQuery.lowercase()
        readAsWords.update { keys -> keys.filterTo(HashSet()) { lowered.contains(it.substringAfter(':')) } }
        query.value = newQuery 
    }

    // ════════════════════════════════════════════════════════════════════════
    // Smart Fallback Smart Answer Builder (formerly in AgenticScreen)
    // ════════════════════════════════════════════════════════════════════════

    private fun buildFallbackAnswer(queryText: String, results: List<VaultItem>, direct: DirectAnswer? = null): String {
        if (results.isEmpty()) {
            return "I couldn't find anything matching \"$queryText\" in your vault."
        }

        val qLower = queryText.lowercase()
        val allText = results.map { it.ocrText.trim() }

        val isMoneyQuery = listOf("expense", "spend", "payment", "upi", "paid", "amount",
            "total", "calculate", "money", "transaction", "rupee", "cost", "bill",
            "recharge", "credited", "debited", "received", "transfer").any { qLower.contains(it) }

        if (isMoneyQuery) return buildPaymentAnswer(allText, results)

        if (qLower.contains("otp") || qLower.contains("code") || qLower.contains("verification")) {
            return buildOtpAnswer(allText)
        }

        val isContactQuery = listOf("contact", "number", "call", "missed", "dial", "phone")
            .any { qLower.contains(it) }
        if (isContactQuery) return buildContactAnswer(allText, results)

        // What the best page says about it, in its own words, with where it is — in place of
        // "Found 3 results" and a line from each.
        return direct?.text ?: buildGenericAnswer(qLower, allText, results)
    }

    private fun buildPaymentAnswer(texts: List<String>, results: List<VaultItem>): String {
        data class Transaction(val amount: Int, val name: String, val date: String, val type: String)

        val transactions = mutableListOf<Transaction>()

        texts.forEach { text ->
            Regex("₹\\s?([\\d,]+)").findAll(text).forEach { match ->
                val amount = match.groupValues[1].replace(",", "").toIntOrNull() ?: return@forEach
                val pos = match.range.first
                val contextStart = (pos - 200).coerceAtLeast(0)
                val context = text.substring(contextStart, (pos + 50).coerceAtMost(text.length))
                val contextLower = context.lowercase()

                val type = when {
                    contextLower.contains("received") || contextLower.contains("credited") -> "received"
                    contextLower.contains("paid") || contextLower.contains("debited") ||
                            contextLower.contains("payment") -> "paid"
                    contextLower.contains("recharge") -> "recharge"
                    else -> "transaction"
                }

                val name = Regex("(?:from|to)\\s+([A-Z][A-Za-z ]{2,20})").find(context)
                    ?.groupValues?.getOrNull(1)?.trim() ?: ""

                val date = Regex("\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(?:\\s+\\d{4})?")
                    .find(context)?.value ?: ""

                transactions.add(Transaction(amount, name, date, type))
            }
        }

        if (transactions.isEmpty()) {
            return "Found ${results.size} results but couldn't extract amounts."
        }

        val sb = StringBuilder()
        val totalSpent = transactions.filter { it.type != "received" }.sumOf { it.amount }
        val totalReceived = transactions.filter { it.type == "received" }.sumOf { it.amount }

        sb.append("💰 Found ${transactions.size} transaction${if (transactions.size > 1) "s" else ""}:\n\n")

        transactions.distinctBy { "${it.amount}_${it.name}" }.take(6).forEach { t ->
            val icon = when (t.type) {
                "received" -> "⬇️"; "paid" -> "⬆️"
                "recharge" -> "📱"; else -> "💳"
            }
            val nameStr = if (t.name.isNotBlank()) " — ${t.name}" else ""
            val dateStr = if (t.date.isNotBlank()) " (${t.date})" else ""
            sb.append("$icon ₹${t.amount}$nameStr$dateStr\n")
        }

        if (totalSpent > 0 || totalReceived > 0) {
            sb.append("\n")
            if (totalSpent > 0) sb.append("Spent: ₹$totalSpent\n")
            if (totalReceived > 0) sb.append("Received: ₹$totalReceived\n")
            if (totalSpent > 0 && totalReceived > 0) {
                val net = totalReceived - totalSpent
                sb.append("Net: ${if (net >= 0) "+" else ""}₹$net")
            }
        }

        return sb.toString().trim()
    }

    private fun buildOtpAnswer(texts: List<String>): String {
        texts.forEach { text ->
            val tLower = text.lowercase()
            if (tLower.contains("otp") || tLower.contains("code") || tLower.contains("verif")) {
                val candidates = Regex("\\b(\\d{4,8})\\b").findAll(text)
                    .map { it.groupValues[1] }
                    .filter { it.length in 4..8 && !it.startsWith("20") && !it.startsWith("19") }
                    .toList()

                if (candidates.isNotEmpty()) {
                    return "🔑 OTP: ${candidates.last()}\n\nFrom: ${text.take(80)}…"
                }
            }
        }
        return "Couldn't extract a specific OTP."
    }

    private fun buildContactAnswer(texts: List<String>, results: List<VaultItem>): String {
        val phones = mutableSetOf<String>()
        val contactInfo = mutableListOf<String>()

        texts.forEach { text ->
            Regex("(?:\\+91[\\s-]?)?[6-9][\\d\\s-]{8,12}").findAll(text).forEach { m ->
                val digits = m.value.replace(Regex("[^0-9]"), "")
                when {
                    digits.length == 10 -> phones.add("+91 ${digits.take(5)} ${digits.drop(5)}")
                    digits.length == 12 && digits.startsWith("91") ->
                        phones.add("+${digits.take(2)} ${digits.substring(2, 7)} ${digits.drop(7)}")
                }
            }

            Regex("([A-Z][a-z]{2,15}(?:\\s[A-Z][a-z]{2,15})?)\\s*(?:Mobile|Call|Phone|\\+91|[6-9]\\d{4})")
                .findAll(text).forEach {
                    contactInfo.add(it.groupValues[1])
                }

            Regex("(Missed call|Outgoing call|Incoming call|Video call)[^\\d]*(\\d{1,2}:\\d{2}\\s*(?:am|pm)?)",
                RegexOption.IGNORE_CASE).findAll(text).forEach {
                contactInfo.add("${it.groupValues[1]} — ${it.groupValues[2]}")
            }
        }

        if (phones.isEmpty() && contactInfo.isEmpty()) {
            return buildGenericAnswer("contact", texts, results)
        }

        val sb = StringBuilder("Found ${results.size} result${if (results.size > 1) "s" else ""}:\n\n")
        contactInfo.distinct().take(3).forEach { sb.append("👤 $it\n") }
        phones.distinct().take(5).forEach { sb.append("📱 $it\n") }

        return sb.toString().trim()
    }

    private fun buildGenericAnswer(queryText: String, texts: List<String>, results: List<VaultItem>): String {
        val sb = StringBuilder("Found ${results.size} result${if (results.size > 1) "s" else ""}:\n\n")

        val queryWords = queryText.split(Regex("\\s+")).filter { it.length >= 2 }

        results.take(3).forEach { item ->
            val text = item.ocrText.trim()
            val typeIcon = item.itemType.rowIcon

            val bestLine = text.lines()
                .filter { it.isNotBlank() && it.length > 3 }
                .maxByOrNull { line -> queryWords.count { line.lowercase().contains(it) } }
                ?: text.lines().firstOrNull { it.isNotBlank() }

            sb.append("$typeIcon ${bestLine?.trim()?.take(80) ?: text.take(80)}\n")
        }

        return sb.toString().trim()
    }
}
