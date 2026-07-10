package com.amar.vault

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.*
import com.amar.vault.ui.components.home.*
import com.amar.vault.ui.components.folder.*
import com.amar.vault.ui.components.saved.*
import com.amar.vault.ui.renderengine.models.RichSavedItem
import kotlinx.coroutines.delay

sealed class VaultView {
    object FolderGrid : VaultView()
    data class InsideFolder(val category: String, val displayName: String) : VaultView()
    object AllItems : VaultView()
    object Archive : VaultView()
    object Manage : VaultView()
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onItemClick: (String) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val stashItems by viewModel.richStashItems.collectAsState()
    val archivedItems by viewModel.archivedRichItems.collectAsState()
    val archivedCount by viewModel.archivedCountFlow.collectAsState()
    val categoryCounts by viewModel.categoryCountsFlow.collectAsState()
    val categorySummaries by viewModel.categorySummaries.collectAsState()
    val isSavedLoading by viewModel.isSavedLoading.collectAsState()
    val prefs by viewModel.savedPrefs.collectAsState()
    val undo by viewModel.savedUndo.collectAsState()

    var currentView by remember { mutableStateOf<VaultView>(VaultView.FolderGrid) }

    // Mini in-Saved search (final UX): filters the already-loaded list; no DB query,
    // no new screen. Grid state is hoisted so the folder grid's scroll position is
    // restored when search closes.
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val folderGridState = rememberLazyStaggeredGridState()
    // null = follow the adaptive default; non-null = user pinch-zoom override
    var userColumns by remember { mutableStateOf<Int?>(null) }
    var lastScaleTime by remember { mutableStateOf(0L) }

    var activeDetailItem by remember { mutableStateOf<RichSavedItem?>(null) }

    // Selection (multi-select) state
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showMoveSheet by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }

    // Per-list content filter (ephemeral)
    var typeFilter by remember { mutableStateOf(SavedTypeFilter.ALL) }
    val currentSort = remember(prefs.sort) {
        runCatching { SavedSortOption.valueOf(prefs.sort) }.getOrDefault(SavedSortOption.NEWEST)
    }

    fun exitSelection() { selectionMode = false; selectedIds.clear() }

    // Reset transient state whenever the view changes.
    LaunchedEffect(currentView) { exitSelection(); typeFilter = SavedTypeFilter.ALL }

    // Auto-dismiss the undo snackbar.
    LaunchedEffect(undo?.token) {
        if (undo != null) { delay(5000); viewModel.clearUndo() }
    }

    // ── Back navigation audit (#2): never skip a level ────────────────────────
    // Precedence: detail overlay → selection mode → sub-view → Home. Any open
    // ModalBottomSheet registers its own (later) BackHandler and is consumed
    // first, so it isn't handled here. Home → Exit stays the default behavior.
    BackHandler(enabled = true) {
        when {
            activeDetailItem != null -> activeDetailItem = null
            searchActive -> { searchActive = false; searchQuery = "" }
            selectionMode -> exitSelection()
            currentView !is VaultView.FolderGrid -> currentView = VaultView.FolderGrid
            else -> onBack()
        }
    }

    val screenBg = Cream
    val cardBg = CreamLight
    val primaryText = CharcoalSoft
    val secondaryText = WarmBrownDark
    val borderColor = CreamDark

    // Folder ordering: pinned first, then user-defined order, then the rest.
    val orderComparator = compareByDescending<String> { it in prefs.pinned }
        .thenBy { val i = prefs.categoryOrder.indexOf(it); if (i < 0) Int.MAX_VALUE else i }
    val orderedCategories = remember(categoryCounts, prefs.categoryOrder, prefs.pinned) {
        categoryCounts.sortedWith(compareBy(orderComparator) { it.category })
    }
    val orderedSummaries = remember(categorySummaries, prefs.categoryOrder, prefs.pinned) {
        categorySummaries.sortedWith(compareBy(orderComparator) { it.category })
    }

    // Publish the top folders as Android Direct Share targets (#2). Republishes when
    // the folder set changes; pinned/ordered folders lead. Share-surface only.
    LaunchedEffect(orderedSummaries) {
        com.amar.vault.share.ShareTargetPublisher.publish(context, orderedSummaries.map { it.category })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBg)
    ) {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Adaptive base column count; pinch-zoom overrides via userColumns.
        val adaptiveColumns = when {
            maxWidth < 600.dp -> 2
            maxWidth < 900.dp -> 3
            else -> 4
        }
        val effectiveColumns = (userColumns ?: adaptiveColumns).coerceIn(1, 5)
        AnimatedContent(
            targetState = currentView,
            transitionSpec = {
                // Immersive open (#6): entering a folder expands into it (content
                // scales up from the tile + fades in); returning shrinks back into
                // the grid. Feels like stepping into a different space, not a
                // sideways screen swap. The folder-accent color-wash lands in Phase B.
                if (targetState is VaultView.FolderGrid) {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 1.06f, animationSpec = tween(280, easing = EaseOutQuad))) togetherWith
                        (fadeOut(tween(150)) + scaleOut(targetScale = 0.94f, animationSpec = tween(220)))
                } else {
                    (fadeIn(tween(240, delayMillis = 30)) + scaleIn(initialScale = 0.92f, animationSpec = tween(300, easing = EaseOutQuad))) togetherWith
                        (fadeOut(tween(170)) + scaleOut(targetScale = 1.05f, animationSpec = tween(240)))
                }
            },
            label = "VaultViewTransition"
        ) { viewState ->
            when (viewState) {
                is VaultView.FolderGrid -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(54.dp))

                        // The compact nav bar smoothly swaps to an expanding search
                        // field when search is toggled — no new screen, background stays.
                        AnimatedContent(
                            targetState = searchActive,
                            transitionSpec = {
                                (fadeIn(tween(180)) + expandHorizontally(expandFrom = Alignment.End)) togetherWith
                                    (fadeOut(tween(120)) + shrinkHorizontally(shrinkTowards = Alignment.End))
                            },
                            label = "SavedSearchBarSwap"
                        ) { searching ->
                            if (searching) {
                                SavedSearchBar(
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    onClose = { searchActive = false; searchQuery = "" }
                                )
                            } else {
                                val savesLabel = if (stashItems.size == 1) "1 save" else "${stashItems.size} saves"
                                val foldersLabel = if (orderedSummaries.size == 1) "1 folder" else "${orderedSummaries.size} folders"
                                SavedTopBar(
                                    title = "Saved",
                                    subtitle = "$savesLabel  ·  $foldersLabel",
                                    onBack = onBack,
                                    actions = {
                                        NavIconButton("🔍", contentDesc = "Search saved items") { searchActive = true }
                                        Spacer(Modifier.width(Spacing.S))
                                        NavIconButton("⚙", contentDesc = "Manage folders") { currentView = VaultView.Manage }
                                        Spacer(Modifier.width(Spacing.S))
                                        NavIconButton("🗄", badge = archivedCount.takeIf { it > 0 }, contentDesc = "Archive") {
                                            currentView = VaultView.Archive
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(Spacing.S))

                        if (searchActive) {
                            // ── Mini-search results: pure in-memory filter of the
                            // already-loaded list. Folder cards are hidden; only
                            // matching saved items show. No DB query, no spinner.
                            val results = remember(searchQuery, stashItems) {
                                SavedSearch.filter(stashItems, searchQuery)
                            }
                            SavedResultCount(count = results.size, hasQuery = searchQuery.isNotBlank())
                            if (results.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    contentAlignment = Alignment.Center
                                ) { NoResultsState(searchQuery) }
                            } else {
                                SavedItemsGrid(
                                    items = results,
                                    columnCount = effectiveColumns,
                                    selectionMode = false,
                                    selectedIds = emptyList(),
                                    onOpen = { item -> activeDetailItem = item; viewModel.recordOpened(item.id) },
                                    onToggleSelect = {},
                                    onStartSelection = {},
                                    onZoomIn = {
                                        val now = System.currentTimeMillis()
                                        if (now - lastScaleTime > 600) { userColumns = (effectiveColumns - 1).coerceIn(1, 5); lastScaleTime = now }
                                    },
                                    onZoomOut = {
                                        val now = System.currentTimeMillis()
                                        if (now - lastScaleTime > 600) { userColumns = (effectiveColumns + 1).coerceIn(1, 5); lastScaleTime = now }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        } else if (isSavedLoading && stashItems.isEmpty()) {
                            SavedSkeletonGridWrapper(adaptiveColumns, Modifier.weight(1f))
                        } else if (stashItems.isEmpty() && orderedSummaries.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 60.dp),
                                contentAlignment = Alignment.Center
                            ) { EmptySavedState(primaryText, secondaryText) }
                        } else {
                            Column(modifier = Modifier.weight(1f)) {
                                if (stashItems.isNotEmpty()) {
                                    RecentActivityRail(
                                        items = stashItems,
                                        recentlyOpened = prefs.recentlyOpened,
                                        recentlyMoved = prefs.recentlyMoved,
                                        recentlyFavorited = prefs.recentlyFavorited,
                                        onOpen = { item ->
                                            activeDetailItem = item; viewModel.recordOpened(item.id)
                                        }
                                    )
                                    Spacer(Modifier.height(Spacing.M))
                                }
                                FolderGrid(
                                    itemCount = stashItems.size,
                                    summaries = orderedSummaries,
                                    onAllItemsClick = { currentView = VaultView.AllItems },
                                    onCategoryClick = { cat, name ->
                                        currentView = VaultView.InsideFolder(cat, name)
                                    },
                                    onCreateFolderClick = { showCreateFolder = true },
                                    modifier = Modifier.weight(1f),
                                    pinnedCategories = prefs.pinned,
                                    folderMeta = prefs.folderMeta,
                                    gridState = folderGridState,
                                    columns = adaptiveColumns
                                )
                            }
                        }
                    }
                }

                is VaultView.Manage -> {
                    ManageCategoriesScreen(
                        categories = orderedCategories,
                        pinned = prefs.pinned,
                        onBack = { currentView = VaultView.FolderGrid },
                        onRename = viewModel::renameCategory,
                        onMerge = viewModel::mergeCategory,
                        onDelete = viewModel::deleteCategory,
                        onTogglePin = viewModel::togglePinCategory,
                        onReorder = viewModel::setCategoryOrder
                    )
                }

                is VaultView.InsideFolder, is VaultView.AllItems, is VaultView.Archive -> {
                    val isAll = viewState is VaultView.AllItems
                    val isArchive = viewState is VaultView.Archive
                    val categoryFilter = (viewState as? VaultView.InsideFolder)?.category
                    val title = when (viewState) {
                        is VaultView.InsideFolder -> viewState.displayName
                        is VaultView.Archive -> "Archive"
                        else -> "All Items"
                    }

                    val baseItems = if (isArchive) archivedItems else stashItems
                    val scopedItems = remember(baseItems, categoryFilter) {
                        if (categoryFilter == null) baseItems
                        else baseItems.filter { it.rawStashItem.category == categoryFilter }
                    }
                    val availableFilters = remember(scopedItems) { SavedOrganizer.availableFilters(scopedItems) }
                    val visibleItems = remember(scopedItems, typeFilter, currentSort, prefs.recentlyOpened) {
                        SavedOrganizer.sort(
                            SavedOrganizer.filter(scopedItems, typeFilter),
                            currentSort,
                            prefs.recentlyOpened
                        )
                    }

                    // Theme carry-through (#4): the folder's accent washes the top of
                    // the view, so opening a themed folder feels like entering its space.
                    val viewAccent = categoryFilter?.let { prefs.folderMeta[it] }?.let { Color(it.accent) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (viewAccent != null) Modifier.background(
                                    Brush.verticalGradient(
                                        0f to viewAccent.copy(alpha = 0.14f),
                                        0.32f to Color.Transparent
                                    )
                                ) else Modifier
                            )
                    ) {
                        Spacer(Modifier.height(54.dp))

                        if (selectionMode) {
                            SavedSelectionBar(
                                count = selectedIds.size,
                                isArchiveContext = isArchive,
                                onClose = { exitSelection() },
                                onSelectAll = {
                                    selectedIds.clear()
                                    selectedIds.addAll(visibleItems.map { it.id })
                                },
                                onMove = { if (selectedIds.isNotEmpty()) showMoveSheet = true },
                                onFavorite = {
                                    viewModel.bulkFavorite(selectedIds.toList(), true); exitSelection()
                                },
                                onArchiveToggle = {
                                    if (isArchive) viewModel.bulkUnarchive(selectedIds.toList())
                                    else viewModel.bulkArchive(selectedIds.toList())
                                    exitSelection()
                                },
                                onDelete = {
                                    viewModel.bulkDelete(selectedIds.toList()); exitSelection()
                                }
                            )
                        } else {
                            val countLabel = if (visibleItems.size == 1) "1 save" else "${visibleItems.size} saves"
                            SavedTopBar(
                                title = title,
                                subtitle = countLabel,
                                onBack = { currentView = VaultView.FolderGrid },
                                accent = viewAccent
                            )
                        }

                        if (visibleItems.isNotEmpty() && !selectionMode) {
                            SavedSortFilterRow(
                                currentSort = currentSort,
                                onSortSelected = viewModel::setSavedSort,
                                filters = availableFilters,
                                selectedFilter = typeFilter,
                                onFilterSelected = { typeFilter = it },
                                modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.S)
                            )
                        }

                        if (isSavedLoading && baseItems.isEmpty()) {
                            SavedSkeletonGridWrapper(effectiveColumns, Modifier.weight(1f))
                        } else if (visibleItems.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                if (isArchive) ArchiveEmptyState() else EmptyFolderState()
                            }
                        } else {
                            SavedItemsGrid(
                                items = visibleItems,
                                columnCount = effectiveColumns,
                                selectionMode = selectionMode,
                                selectedIds = selectedIds,
                                onOpen = { item ->
                                    activeDetailItem = item
                                    viewModel.recordOpened(item.id)
                                },
                                onToggleSelect = { id ->
                                    if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
                                },
                                onStartSelection = { id ->
                                    selectionMode = true
                                    if (id !in selectedIds) selectedIds.add(id)
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onZoomIn = {
                                    val now = System.currentTimeMillis()
                                    if (now - lastScaleTime > 600) {
                                        userColumns = (effectiveColumns - 1).coerceIn(1, 5); lastScaleTime = now
                                    }
                                },
                                onZoomOut = {
                                    val now = System.currentTimeMillis()
                                    if (now - lastScaleTime > 600) {
                                        userColumns = (effectiveColumns + 1).coerceIn(1, 5); lastScaleTime = now
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
      } // BoxWithConstraints

        // ── Detail overlay (premium zoom-in) ──────────────────────────────────
        var retainedDetail by remember { mutableStateOf<RichSavedItem?>(null) }
        var detailRelated by remember { mutableStateOf<List<RichSavedItem>>(emptyList()) }
        var detailMoveItemId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(activeDetailItem) { activeDetailItem?.let { retainedDetail = it } }
        LaunchedEffect(activeDetailItem?.id) {
            detailRelated = activeDetailItem?.let { viewModel.fetchRelated(it) } ?: emptyList()
        }
        AnimatedVisibility(
            visible = activeDetailItem != null,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(260, easing = EaseOutQuad)),
            exit = fadeOut(tween(160)) + scaleOut(targetScale = 0.94f, animationSpec = tween(180))
        ) {
            retainedDetail?.let { originalRichItem ->
                val liveList = if (originalRichItem.rawStashItem.vaultType == "ARCHIVED") archivedItems else stashItems
                val latestRichItem = liveList.find { it.id == originalRichItem.id } ?: originalRichItem
                val presentation = com.amar.vault.ui.detail.core.DetailPresentationBuilder.build(
                    item = latestRichItem,
                    onAction = { },
                    related = detailRelated
                )
                com.amar.vault.ui.detail.DetailScreen(
                    item = latestRichItem,
                    presentation = presentation,
                    onBack = { activeDetailItem = null },
                    onRelatedClick = { rel -> activeDetailItem = rel; viewModel.recordOpened(rel.id) },
                    onAction = { actionId ->
                        val stash = latestRichItem.rawStashItem
                        when (actionId) {
                            "open", "play", "listen", "read", "visit", "open_pdf", "buy" ->
                                ContentOpenManager.open(context, stash)
                            "open_app" -> ContentOpenManager.openOriginal(context, stash)
                            "share" -> ContentOpenManager.share(context, stash)
                            "copy_link" -> ContentOpenManager.copyLink(context, stash)
                            "favorite" -> viewModel.toggleSavedFavorite(latestRichItem.id, !latestRichItem.isFavorite)
                            "move" -> detailMoveItemId = latestRichItem.id
                            "remove_category" -> viewModel.moveSavedCategory(latestRichItem.id, "")
                            "archive" -> { viewModel.bulkArchive(listOf(latestRichItem.id)); activeDetailItem = null }
                            "unarchive" -> { viewModel.bulkUnarchive(listOf(latestRichItem.id)); activeDetailItem = null }
                            "delete" -> { viewModel.deleteSavedItem(latestRichItem.id); activeDetailItem = null }
                        }
                    }
                )
            }
        }

        // Single-item move sheet launched from the detail page.
        detailMoveItemId?.let { moveId ->
            MoveToCategorySheet(
                categories = categoryCounts.map { it.category },
                onDismiss = { detailMoveItemId = null },
                onMove = { target ->
                    viewModel.moveSavedCategory(moveId, target)
                    detailMoveItemId = null
                }
            )
        }

        // ── Create-folder sheet (#3) ──────────────────────────────────────────
        if (showCreateFolder) {
            CreateFolderSheet(
                existingNames = orderedSummaries.map { it.category }.filter { it.isNotBlank() },
                onDismiss = { showCreateFolder = false },
                onCreate = { name, meta ->
                    viewModel.createFolder(name, meta)
                    showCreateFolder = false
                    currentView = VaultView.InsideFolder(name, name)
                }
            )
        }

        // ── Move-to-category sheet (bulk selection) ────────────────────────────
        if (showMoveSheet) {
            MoveToCategorySheet(
                categories = categoryCounts.map { it.category },
                onDismiss = { showMoveSheet = false },
                onMove = { target ->
                    viewModel.bulkMove(selectedIds.toList(), target)
                    showMoveSheet = false
                    exitSelection()
                }
            )
        }

        // ── Undo snackbar ──────────────────────────────────────────────────────
        SavedUndoSnackbar(
            undo = undo,
            onUndo = { viewModel.performUndo() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.XL)
        )
    }
}

// ── Reusable item grid with selection + pinch-to-zoom ───────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedItemsGrid(
    items: List<RichSavedItem>,
    columnCount: Int,
    selectionMode: Boolean,
    selectedIds: List<String>,
    onOpen: (RichSavedItem) -> Unit,
    onToggleSelect: (String) -> Unit,
    onStartSelection: (String) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columnCount),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.L)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom > 1.3f) onZoomIn() else if (zoom < 0.7f) onZoomOut()
                }
            }
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
            val selected = item.id in selectedIds
            Box(modifier = Modifier.animateItem()) {
                val renderer = com.amar.vault.ui.renderengine.core.CardRendererFactory.createRenderer(item.contentType)
                renderer.Render(
                    item = item,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (selectionMode && selected) Modifier.alpha(0.7f) else Modifier)
                        .bounceClick(
                            onLongClick = { if (!selectionMode) onStartSelection(item.id) },
                            onClick = { if (selectionMode) onToggleSelect(item.id) else onOpen(item) }
                        )
                )
                if (selectionMode) {
                    SelectionIndicator(
                        selected = selected,
                        accent = item.style.accent,
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(if (selected) accent else Color.Black.copy(alpha = 0.35f))
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

// ── Compact Saved nav bar (#1) ──────────────────────────────────────────────
// One 56dp bar shared by every Saved view: a circular back affordance, an inline
// title + count (the content is the hero, not a giant heading), and optional
// trailing action buttons. Unifies spacing/typography across views (#9).
@Composable
private fun SavedTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    accent: Color? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = Spacing.L),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavIconButton("←", tint = accent, contentDesc = "Back", onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = CharcoalSoft,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = WarmBrownDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

@Composable
private fun NavIconButton(
    glyph: String,
    badge: Int? = null,
    tint: Color? = null,
    contentDesc: String? = null,
    onClick: () -> Unit
) {
    Box(contentAlignment = Alignment.TopEnd) {
        Surface(
            modifier = Modifier
                .bounceClick { onClick() }
                .size(40.dp)
                .then(
                    if (contentDesc != null) Modifier.semantics {
                        this.contentDescription = contentDesc
                        this.role = Role.Button
                    } else Modifier
                ),
            shape = CircleShape,
            color = tint?.copy(alpha = 0.16f) ?: CreamLight.copy(alpha = 0.7f),
            border = BorderStroke(0.5.dp, (tint ?: CreamDark).copy(alpha = 0.6f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(glyph, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = tint ?: WarmBrownDark)
            }
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .padding(top = 1.dp, end = 1.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(ReelColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badge > 9) "9+" else badge.toString(),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

// ── Mini in-Saved search bar ────────────────────────────────────────────────
@Composable
private fun SavedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Auto-focus + open the keyboard the moment search expands.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Spacing.L),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavIconButton("←", contentDesc = "Close search", onClick = onClose)
        Spacer(Modifier.width(12.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(CreamLight)
                .border(BorderStroke(0.5.dp, CreamDark), RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🔍", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Search saved items", color = WarmBrownDark.copy(alpha = 0.6f), fontSize = 15.sp)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = CharcoalSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    cursorBrush = SolidColor(CharcoalSoft),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(CreamDark)
                        .bounceClick { onQueryChange("") },
                    contentAlignment = Alignment.Center
                ) { Text("✕", fontSize = 11.sp, color = WarmBrownDark, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SavedResultCount(count: Int, hasQuery: Boolean) {
    val text = when {
        !hasQuery -> if (count == 1) "1 item" else "$count items"
        count == 0 -> "No results"
        count == 1 -> "1 result"
        else -> "$count results"
    }
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = WarmBrownDark,
        modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.XS)
    )
}

@Composable
private fun NoResultsState(query: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(24.dp)
    ) {
        Text("🔍", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text("No results", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (query.isBlank()) "Type to search your saved items." else "Nothing matches “$query”.",
            fontSize = 13.sp,
            color = WarmBrownDark,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun SavedSkeletonGridWrapper(columns: Int, modifier: Modifier) {
    com.amar.vault.ui.renderengine.components.SavedSkeletonGrid(
        columns = columns,
        modifier = modifier.padding(horizontal = Spacing.L)
    )
}

@Composable
private fun ArchiveEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🗄", fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text("Nothing archived", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft)
        Spacer(Modifier.height(6.dp))
        Text(
            "Archived items are tucked away here, out of your main Saved feed.",
            fontSize = 13.sp,
            color = WarmBrownDark,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}

// Redesigned empty state (Phase 2)
@Composable
private fun EmptySavedState(primaryText: Color, secondaryText: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    BorderStroke(1.dp, Brush.sweepGradient(listOf(Color.LightGray, Color.Gray.copy(alpha = 0.5f)))),
                    RoundedCornerShape(12.dp)
                )
                .background(Color.LightGray.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color.LightGray.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) { Text("🎬", fontSize = 16.sp) }
                Spacer(Modifier.height(12.dp))
                Text("Ghost Reel", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = secondaryText.copy(alpha = 0.5f))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("Nothing saved yet.", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = primaryText, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Share reels, screenshots, links, docs, or voice memos from other apps to Amar Vault and they will appear here automatically.",
            fontSize = 13.sp,
            color = secondaryText,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}
