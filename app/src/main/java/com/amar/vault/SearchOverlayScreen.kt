package com.amar.vault

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.Context
import com.amar.vault.share.open.ShareContentUri
import com.amar.vault.ui.theme.*

/**
 * Opens a stored PDF in the in-app viewer on the page the query was found on. False for
 * anything that is not one (a Word file, a link to a PDF); the caller opens those its own way.
 */
private fun openAtMatch(context: Context, hit: SearchHit, query: String): Boolean {
    val item = hit.row
    if (ContentSpecies.classify(item) != ContentSpecies.PDF || item.uri.startsWith("http", ignoreCase = true)) return false
    val uri = ShareContentUri.resolve(context, item.uri) ?: return false
    PdfViewerActivity.open(
        context = context,
        uri = uri,
        // Found by its name only: no page to go to, so the viewer returns to where it was left.
        page = hit.page ?: 0,
        searchQuery = if (hit.page != null) hit.similarWord ?: query.trim() else "",
        fileName = item.title ?: item.sourceFile,
        matchText = hit.excerpt.orEmpty(),
    )
    return true
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchOverlayScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onResultClick: (String) -> Unit,
    onEntityClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val queryText by viewModel.query.collectAsState()
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()
    val resultsList by viewModel.searchHits.collectAsState()
    val activeFilter by viewModel.activeFilter.collectAsState()
    val quickFilters = viewModel.quickFilters
    val recentSearches by viewModel.recentSearches.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()
    val similarSpellingsOnly by viewModel.similarSpellingsOnly.collectAsState()
    val understood by viewModel.understood.collectAsState()
    val readingNote by viewModel.readingNote.collectAsState()
    val photoMatches by viewModel.photoMatches.collectAsState()

    // Detailed overlay for search results spatial continuity
    var activeDetailItem by remember { mutableStateOf<StashItemWithVaultItem?>(null) }
    // The result the user has asked to take out of the vault, until they confirm or cancel.
    var toRemove by remember { mutableStateOf<StashItemWithVaultItem?>(null) }
    
    // Each result is the card to show plus, for a document, the page and words that matched.
    val mappedResults = resultsList

    // The results that say one of the query's words as a word come first; those found only
    // inside longer words ("handbooks" for "hand") or by its small words alone ("the") are folded
    // under one line. The order the search gave is kept within each.
    val (direct, partial) = remember(mappedResults, queryText, similarSpellingsOnly) {
        if (similarSpellingsOnly) mappedResults to emptyList()
        else {
            val words = ResultWording.contentWords(queryText)
            mappedResults.partition { it.filedUnder != null || ResultWording.saysAWord(it, words) }
        }
    }
    var showPartial by remember(queryText) { mutableStateOf(false) }
    // With nothing else to show, the partial matches are not hidden behind a tap.
    val partialOpen = showPartial || (direct.isEmpty() && photoMatches.isEmpty())

    // One result's card, as each kind is shown.
    val resultCard: @Composable (SearchHit, Modifier) -> Unit = { hit, cardModifier ->
        val item = hit.row
        val isImage = item.itemType.isImage
        val isDocument = remember(item) {
            !isImage && (ContentSpecies.classify(item) == ContentSpecies.PDF ||
                ContentSpecies.isOfficeDocument(item.itemType, item.mimeType, item.uri))
        }
        if (isImage) {
            // A picture is shown as the picture, with the words read off it that matched, and
            // opens full screen. Its details are a long press away.
            com.amar.vault.ui.components.search.ImageHitCard(
                hit = hit,
                query = queryText,
                onClick = {
                    viewModel.recordSearch(queryText)
                    AmarImageViewerActivity.open(context, item.toVaultItem())
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    activeDetailItem = item
                },
                modifier = cardModifier
            )
        } else if (isDocument) {
            // A document shows the words that matched and opens on their page. What the card
            // used to open — folder, note, delete — is a long press away.
            com.amar.vault.ui.components.search.DocumentHitCard(
                hit = hit,
                query = queryText,
                onClick = {
                    viewModel.recordSearch(queryText)
                    if (!openAtMatch(context, hit, queryText)) activeDetailItem = item
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    activeDetailItem = item
                },
                modifier = cardModifier
            )
        } else {
            // Every other kind keeps the shared card, drawn from the item as stored.
            CollectibleVaultCard(
                item = item,
                onClick = {
                    viewModel.recordSearch(queryText)
                    activeDetailItem = item
                },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                screenBg = Cream,
                cardBg = CreamLight,
                primaryText = CharcoalSoft,
                secondaryText = WarmBrownDark,
                borderColor = CreamDark,
                modifier = cardModifier
            )
        }
    }

    // The box keeps its text between visits, so search again for what is in the vault now.
    LaunchedEffect(Unit) {
        viewModel.warmUpPhotoSearch()
        viewModel.refreshSearch()
    }

    // Every new result list is shown from its first, best row. A LazyColumn with keys keeps the
    // row that was on top where it is; when a later search stage (or the next keystroke) ranked
    // better matches above that row, they were laid out off-screen above it and the best result
    // looked missing. Once the user has dragged this query's list it is left where they put it.
    val listState = rememberLazyListState()
    var draggedByUser by remember(queryText, activeFilter) { mutableStateOf(false) }
    LaunchedEffect(listState, queryText, activeFilter) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) draggedByUser = true
        }
    }
    // The photos come with the last search stage, after the word results are on screen; laid out
    // above the first of those, they were scrolled half out of sight. They are shown from the top.
    LaunchedEffect(mappedResults, photoMatches) {
        if (!draggedByUser) listState.scrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(Modifier.height(54.dp))

            // Search Header Input Palette
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = CharcoalSoft,
                        modifier = Modifier.size(24.dp)
                    )
                }
                
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { viewModel.updateQuery(it) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    placeholder = { Text("Type to search vault...", color = WarmBrown, fontSize = 15.sp) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, color = CharcoalSoft),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = CreamLight,
                        unfocusedContainerColor = CreamLight,
                        focusedTextColor = CharcoalSoft,
                        unfocusedTextColor = CharcoalSoft
                    ),
                    shape = RoundedCornerShape(28.dp),
                    trailingIcon = {
                        if (queryText.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateQuery("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = WarmBrown,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }

            // What the app read into the query beyond words to look for. Each chip says the
            // words it was read from and what they were read as; a tap takes that reading back.
            if (understood.isNotEmpty() && queryText.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    understood.forEach { reading ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(CreamLight)
                                .border(1.dp, CreamDark, RoundedCornerShape(12.dp))
                                .clickable(onClickLabel = "Remove this reading") { viewModel.takeBack(reading) }
                                .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = reading.chip, fontSize = 12.sp, color = CharcoalSoft)
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Remove: ${reading.label}",
                                tint = WarmBrown,
                                modifier = Modifier.padding(start = 4.dp).size(14.dp)
                            )
                        }
                    }
                }
            }
            readingNote?.takeIf { queryText.isNotBlank() }?.let { note ->
                Text(
                    text = note,
                    color = WarmBrownDark,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            // Quick Filters
            com.amar.vault.ui.components.search.QuickFiltersRow(
                filters = quickFilters,
                activeFilter = activeFilter,
                onFilterSelected = { viewModel.updateFilter(it) }
            )

            Divider(color = CreamDark, thickness = 1.dp)

            // Dynamic Content Area
            if (isSearchLoading && queryText.isNotBlank()) {
                com.amar.vault.ui.components.search.LoadingSkeletonView()
            } else if (queryText.isBlank()) {
                com.amar.vault.ui.components.search.RecentSearchesView(
                    recentSearches = recentSearches,
                    onSearchSelected = { viewModel.updateQuery(it) },
                    onDeleteSearch = { viewModel.deleteRecentSearch(it) },
                    onClearAll = { viewModel.clearHistory() }
                )
            } else if (mappedResults.isEmpty() && photoMatches.isEmpty() && activeFilter != SearchFilter.ALL) {
                // Nothing under this chip: say that it is the chip, and offer the way out of it.
                com.amar.vault.ui.components.search.EmptyStateView(
                    query = queryText,
                    filter = activeFilter,
                    onSearchEverything = { viewModel.updateFilter(SearchFilter.ALL) }
                )
            } else if (mappedResults.isEmpty() && photoMatches.isEmpty() && searchSuggestions.isNotEmpty()) {
                com.amar.vault.ui.components.search.SearchSuggestionsView(
                    suggestions = searchSuggestions,
                    onSuggestionSelected = { viewModel.updateQuery(it) }
                )
            } else if (mappedResults.isEmpty() && photoMatches.isEmpty()) {
                com.amar.vault.ui.components.search.EmptyStateView(query = queryText)
            } else {
                // Entity Page entry point — projects the current query as an entity over the
                // existing search results (read-only; no new retrieval behaviour).
                androidx.compose.material3.TextButton(
                    onClick = { onEntityClick(queryText) },
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Text("See everything about \"${queryText.trim()}\"", color = WarmBrown, fontSize = 14.sp)
                }
                // Look-alikes are said to be look-alikes, not passed off as matches.
                if (similarSpellingsOnly) {
                    Text(
                        text = "Nothing has \"${queryText.trim()}\" as typed. These have a word spelt nearly the same.",
                        color = WarmBrownDark,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Pictures found by what is in them come first, as a grid; a whole screen of
                    // them when nothing says the query's words.
                    if (photoMatches.isNotEmpty()) {
                        item(key = "photo-grid") {
                            com.amar.vault.ui.components.search.PhotoGrid(
                                photos = photoMatches,
                                shownAtFirst = if (direct.isEmpty()) 12 else 6,
                                onOpen = { photo ->
                                    viewModel.recordSearch(queryText)
                                    AmarImageViewerActivity.open(context, photo.toVaultItem())
                                },
                            )
                        }
                    }
                    items(direct, key = { it.row.stashId }) { hit ->
                        resultCard(hit, Modifier.animateItemPlacement())
                    }
                    // What was found only inside longer words, or only by the query's small words,
                    // is folded under one line: in the list, it buried what was asked for.
                    if (partial.isNotEmpty() && !partialOpen) {
                        item(key = "partial-toggle") {
                            androidx.compose.material3.TextButton(onClick = { showPartial = true }) {
                                Text("Show ${partial.size} partial matches", color = WarmBrownDark, fontSize = 14.sp)
                            }
                        }
                    }
                    if (partialOpen && partial.isNotEmpty()) {
                        if (direct.isEmpty() && photoMatches.isEmpty()) {
                            item(key = "partial-note") {
                                Text(
                                    text = "Nothing says \"${queryText.trim()}\" as a word. These contain it inside other words.",
                                    color = WarmBrownDark,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                        items(partial, key = { "partial-" + it.row.stashId }) { hit ->
                            resultCard(hit, Modifier.animateItemPlacement())
                        }
                    }
                }
            }
        }

        // Expanded detail view overlay for spatial continuity inside search
        activeDetailItem?.let { originalItem ->
            // A result that was never saved to a folder: a page of a document, a picture.
            val isSaved = !originalItem.stashId.startsWith("search_")
            CollectibleDetailView(
                item = originalItem,
                isSaved = isSaved,
                onBack = { activeDetailItem = null },
                onDelete = {
                    if (isSaved) {
                        viewModel.deleteSavedItem(originalItem.stashId)
                        activeDetailItem = null
                    } else {
                        toRemove = originalItem
                    }
                },
                onFavoriteToggle = {
                    viewModel.toggleSavedFavorite(originalItem.stashId, !originalItem.isFavorite)
                },
                onNoteChange = { note ->
                    viewModel.updateNote(originalItem.stashId, note)
                },
                modifier = Modifier
                    .fillMaxSize()
            )
        }

        toRemove?.let { item ->
            val isDocument = item.parentDocumentId != null
            val name = item.title ?: item.sourceFile.substringAfterLast('/').ifBlank { if (isDocument) "this document" else "this picture" }
            AlertDialog(
                onDismissRequest = { toRemove = null },
                containerColor = CreamLight,
                title = { Text(com.amar.vault.indexing.RemovalWords.question(name, isDocument), color = CharcoalSoft, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                text = { Text(com.amar.vault.indexing.RemovalWords.whatHappens(isDocument), color = WarmBrownDark, fontSize = 14.sp, lineHeight = 20.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        toRemove = null
                        activeDetailItem = null
                        viewModel.removeFromVault(item)
                    }) { Text("Remove", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { toRemove = null }) { Text("Keep", color = WarmBrownDark) }
                },
            )
        }
    }
}
