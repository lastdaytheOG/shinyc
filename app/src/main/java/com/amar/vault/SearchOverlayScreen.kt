package com.amar.vault

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.amar.vault.ui.theme.*

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
    val resultsList by viewModel.results.collectAsState()
    val activeFilter by viewModel.activeFilter.collectAsState()
    val quickFilters = viewModel.quickFilters
    val recentSearches by viewModel.recentSearches.collectAsState()
    val searchSuggestions by viewModel.searchSuggestions.collectAsState()

    // Detailed overlay for search results spatial continuity
    var activeDetailItem by remember { mutableStateOf<StashItemWithVaultItem?>(null) }
    
    // Results come directly as StashItemWithVaultItem from updated ViewModel
    val mappedResults = resultsList

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

            // Advanced-search operator chips (parsed from the query — presentation only).
            val parsedOps = remember(queryText) { SearchOperators.parse(queryText) }
            if (parsedOps.hasAny) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    parsedOps.chips.forEach { chip ->
                        Text(
                            text = chip,
                            fontSize = 12.sp,
                            color = CharcoalSoft,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(CreamLight)
                                .border(1.dp, CreamDark, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    TextButton(onClick = { viewModel.updateQuery(parsedOps.cleanedQuery) }) {
                        Text("Clear filters", fontSize = 12.sp, color = WarmBrown)
                    }
                }
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
                    Text("See everything about \"$queryText\"", color = WarmBrown, fontSize = 14.sp)
                }
                // To keep it simple and abide by "Do not modify the rendering layer",
                // we pass the exact original items to CollectibleVaultCard, avoiding complex highlighted text injections.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(mappedResults, key = { it.stashId }) { item ->
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
