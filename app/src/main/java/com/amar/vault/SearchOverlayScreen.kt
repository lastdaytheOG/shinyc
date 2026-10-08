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

    // Detailed overlay for search results spatial continuity
    var activeDetailItem by remember { mutableStateOf<StashItemWithVaultItem?>(null) }
    
    // Each result is the card to show plus, for a document, the page and words that matched.
    val mappedResults = resultsList

    // The box keeps its text between visits, so search again for what is in the vault now.
    LaunchedEffect(Unit) { viewModel.refreshSearch() }

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
    LaunchedEffect(mappedResults) {
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
            } else if (mappedResults.isEmpty() && activeFilter != SearchFilter.ALL) {
                // Nothing under this chip: say that it is the chip, and offer the way out of it.
                com.amar.vault.ui.components.search.EmptyStateView(
                    query = queryText,
                    filter = activeFilter,
                    onSearchEverything = { viewModel.updateFilter(SearchFilter.ALL) }
                )
            } else if (mappedResults.isEmpty() && searchSuggestions.isNotEmpty()) {
                com.amar.vault.ui.components.search.SearchSuggestionsView(
                    suggestions = searchSuggestions,
                    onSuggestionSelected = { viewModel.updateQuery(it) }
                )
            } else if (mappedResults.isEmpty()) {
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
                    items(mappedResults, key = { it.row.stashId }) { hit ->
                        val item = hit.row
                        val isImage = item.itemType.isImage
                        val isDocument = remember(item) {
                            !isImage && (ContentSpecies.classify(item) == ContentSpecies.PDF ||
                                ContentSpecies.isOfficeDocument(item.itemType, item.mimeType, item.uri))
                        }
                        if (isImage) {
                            // A picture is shown as the picture, with the words read off it that
                            // matched, and opens full screen. Its details are a long press away.
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
                                modifier = Modifier.animateItemPlacement()
                            )
                        } else if (isDocument) {
                            // A document shows the words that matched and opens on their page.
                            // What the card used to open — folder, note, delete — is a long press away.
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
                                modifier = Modifier.animateItemPlacement()
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
                                modifier = Modifier.animateItemPlacement()
                            )
                        }
                    }
                }
            }
        }

        // Expanded detail view overlay for spatial continuity inside search
        activeDetailItem?.let { originalItem ->
            CollectibleDetailView(
                item = originalItem,
                onBack = { activeDetailItem = null },
                onDelete = {
                    viewModel.deleteSavedItem(originalItem.stashId)
                    activeDetailItem = null
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
    }
}
