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

    val activeFilter = MutableStateFlow("All")
    val recentSearches = MutableStateFlow<List<String>>(emptyList())
    val searchSuggestions = MutableStateFlow<List<String>>(emptyList())
    val quickFilters = listOf("All", "Images", "Videos", "Articles", "Products", "Music", "Documents", "Favorites", "Folders")
    
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

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<StashItemWithVaultItem>> = combine(query, activeFilter) { q, filter ->
        Pair(q, filter)
    }
        .debounce(250)
        .distinctUntilChanged()
        .transformLatest { (q, _) ->
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
                emit(emptyList())
                return@transformLatest
            }
            isSearchLoading.value = true
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
                val base: List<StashItemWithVaultItem> = if (effectiveQuery.isBlank()) {
                    // Operator-only query (e.g. "type:pdf"): no free-text to rank — nothing for the
                    // retrieval engine to score, so browse the SAME Room source of truth the engine
                    // hydrates from (timestamp DESC) and let the operators narrow it. Not a second
                    // ranking path: any free text routes through retrievalService above.
                    db.vaultDao().getAll().map { vi -> savedByVaultId[vi.id] ?: vi.toStashWrapper() }
                } else {
                    val plan = QueryPlanner.parse(effectiveQuery)
                    retrievalService.retrieve(
                        com.amar.vault.retrieval.RetrievalRequest(plan.cleanedQuery, plan)
                    ).items.map { vi -> savedByVaultId[vi.id] ?: vi.toStashWrapper() }
                }
                // Operators refine (narrow) the ranked results — never re-rank or bypass.
                val out = if (ops.hasAny) applyOperators(base, ops) else base
                emit(out)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("SearchVM", "Search failed", e); emit(emptyList()) }
            finally { isSearchLoading.value = false }
        }
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
        category = tags,
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
        mimeType = mimeType
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
                (!ops.requireOcr || item.ocrText.substringBefore("\n[").trim().isNotBlank()) &&
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
                val plan = QueryPlanner.parse(routed.cleanedQuery)
                val searchResults = retrievalService.retrieve(
                    com.amar.vault.retrieval.RetrievalRequest(plan.cleanedQuery, plan)
                ).items
                val sources = searchResults.take(3)

                // 3. Resolve cards if applicable
                val aggregator = KnowledgeAggregator(context)
                var kCard: KnowledgeCard? = null
                var cCard: CollectionCard? = null
                
                val resolution = CanonicalEntityRegistry.resolve(q)
                if (resolution.decision == CanonicalizationDecision.RESOLVED) {
                    kCard = aggregator.buildCard(resolution.canonicalId!!)
                }

                val classConstraint = plan.strict.find { it.type == "DOCUMENT_CLASS" }
                if (classConstraint != null) {
                    try {
                        val docClass = DocumentClass.valueOf(classConstraint.value)
                        cCard = CollectionAggregator(context).buildCard(docClass)
                    } catch (_: Exception) {}
                }

                // 4. Invoke appropriate engine based on QueryRouter classification tier
                val finalAnswer = when (routed.tier) {
                    QueryRouter.QueryTier.TIER0_REGEX -> {
                        val answer = buildFallbackAnswer(q, searchResults)
                        "⚡ $answer"
                    }
                    QueryRouter.QueryTier.TIER1_SEARCH -> {
                        val answer = buildFallbackAnswer(q, searchResults)
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
                            buildFallbackAnswer(q, searchResults)
                        }
                    }
                }

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
        query.value = newQuery 
    }

    // ════════════════════════════════════════════════════════════════════════
    // Smart Fallback Smart Answer Builder (formerly in AgenticScreen)
    // ════════════════════════════════════════════════════════════════════════

    private fun buildFallbackAnswer(queryText: String, results: List<VaultItem>): String {
        if (results.isEmpty()) {
            return "I couldn't find anything matching \"$queryText\" in your vault."
        }

        val qLower = queryText.lowercase()
        val allText = results.map { it.ocrText.substringBefore("\n[").trim() }

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

        return buildGenericAnswer(qLower, allText, results)
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
            val text = item.ocrText.substringBefore("\n[").trim()
            val typeIcon = when (item.itemType) {
                "pdf" -> "📄"; "word" -> "📝"; "excel" -> "📊"
                "screenshot" -> "📸"; else -> "🖼"
            }

            val bestLine = text.lines()
                .filter { it.isNotBlank() && it.length > 3 }
                .maxByOrNull { line -> queryWords.count { line.lowercase().contains(it) } }
                ?: text.lines().firstOrNull { it.isNotBlank() }

            sb.append("$typeIcon ${bestLine?.trim()?.take(80) ?: text.take(80)}\n")
        }

        return sb.toString().trim()
    }
}
