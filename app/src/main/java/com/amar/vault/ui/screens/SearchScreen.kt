package com.amar.vault.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amar.vault.*
import com.amar.vault.indexing.DocumentImport
import com.amar.vault.share.open.ShareContentUri
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onNavigateToDocuments: () -> Unit = {},
    onOpenItemDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current

    val queryText by viewModel.query.collectAsState()
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()
    val searchHits by viewModel.searchHits.collectAsState()
    val activeFilter by viewModel.activeFilter.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val similarSpellingsOnly by viewModel.similarSpellingsOnly.collectAsState()
    val understood by viewModel.understood.collectAsState()
    val readingNote by viewModel.readingNote.collectAsState()
    val photoMatches by viewModel.photoMatches.collectAsState()

    val db = remember { VaultDatabase.get(context) }
    val documentsCount by remember { db.vaultDocumentDao().observeCount() }.collectAsState(initial = 0)
    val picturesCount by remember { db.vaultDao().observePictureCount() }.collectAsState(initial = 0)
    val newestDocs by remember { db.vaultDocumentDao().observeNewest(4) }.collectAsState(initial = emptyList())
    val imports by remember { db.documentImportDao().observeAll() }.collectAsState(initial = emptyList())
    val readingImports = imports.filter { !it.isFinished && !it.waitsForCharger }

    var toRemove by remember { mutableStateOf<VaultItem?>(null) }
    var showPartial by remember(queryText) { mutableStateOf(false) }

    val (directHits, partialHits) = remember(searchHits, queryText, similarSpellingsOnly) {
        if (similarSpellingsOnly) emptyList<SearchHit>() to searchHits
        else {
            val words = ResultWording.contentWords(queryText)
            searchHits.partition { it.filedUnder != null || ResultWording.saysAWord(it, words) }
        }
    }

    val partialOpen = showPartial || (directHits.isEmpty() && photoMatches.isEmpty() && !similarSpellingsOnly)
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.warmUpPhotoSearch()
        viewModel.refreshSearch()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Quiet Search Bar (Google Photos / Files style)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(12.dp))

                TextField(
                    value = queryText,
                    onValueChange = { viewModel.updateQuery(it) },
                    placeholder = {
                        Text(
                            text = VaultStrings.searchPlaceholder(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    modifier = Modifier.weight(1f)
                )

                if (queryText.isNotBlank()) {
                    IconButton(
                        onClick = { viewModel.updateQuery("") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Active Reading Banner (Subtle indicator linking to Documents screen)
        if (readingImports.isNotEmpty()) {
            val first = readingImports.first()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .clickable(onClick = onNavigateToDocuments),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (readingImports.size == 1) "Reading ${first.name}..."
                        else "Reading ${first.name} and ${readingImports.size - 1} more...",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Filter Chips Row: All, Photos & Screenshots, Documents
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChipItem(
                label = VaultStrings.filterAll(lang),
                isSelected = activeFilter == SearchFilter.ALL,
                onClick = { viewModel.updateFilter(SearchFilter.ALL) }
            )
            FilterChipItem(
                label = VaultStrings.filterPhotos(lang),
                isSelected = activeFilter == SearchFilter.IMAGES,
                onClick = { viewModel.updateFilter(SearchFilter.IMAGES) }
            )
            FilterChipItem(
                label = VaultStrings.filterDocs(lang),
                isSelected = activeFilter == SearchFilter.DOCUMENTS,
                onClick = { viewModel.updateFilter(SearchFilter.DOCUMENTS) }
            )

            // Dynamic understood tags
            understood.forEach { reading ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.clickable { viewModel.takeBack(reading) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = reading.chip,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove reading",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

        // Main Content: Idle / Glance View vs Results View
        if (queryText.isBlank()) {
            IdleGlanceContent(
                documentsCount = documentsCount,
                picturesCount = picturesCount,
                recentSearches = recentSearches,
                newestDocs = newestDocs,
                lang = lang,
                onSearch = { viewModel.updateQuery(it) },
                onDeleteSearch = { viewModel.deleteRecentSearch(it) },
                onAddDocuments = onNavigateToDocuments,
                onOpenDocument = { doc ->
                    openDocumentAtStart(context, doc)
                }
            )
        } else {
            SearchResultsContent(
                queryText = queryText,
                isLoading = isSearchLoading,
                directHits = directHits,
                partialHits = partialHits,
                photoMatches = photoMatches,
                partialOpen = partialOpen,
                similarSpellingsOnly = similarSpellingsOnly,
                lang = lang,
                listState = listState,
                onShowPartial = { showPartial = true },
                onResultClick = { hit ->
                    viewModel.recordSearch(queryText)
                    openHitAtMatch(context, hit, queryText, onOpenItemDetail)
                },
                onResultLongClick = { hit ->
                    toRemove = hit.row
                },
                onPhotoClick = { photo ->
                    viewModel.recordSearch(queryText)
                    AmarImageViewerActivity.open(context, photo)
                }
            )
        }
    }

    // Item Removal Dialog
    toRemove?.let { item ->
        val isDocument = item.parentDocumentId != null || SearchFilter.isDocument(item)
        val isPicture = item.itemType.isImage
        val name = item.title ?: item.sourceFile.substringAfterLast('/').ifBlank {
            if (isDocument) "this document" else "this item"
        }
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = {
                Text(
                    text = com.amar.vault.indexing.RemovalWords.question(name, isDocument),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = com.amar.vault.indexing.RemovalWords.whatHappens(isDocument, isPicture),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = toRemove
                    toRemove = null
                    if (target != null) viewModel.removeFromVault(target)
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { toRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FilterChipItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun IdleGlanceContent(
    documentsCount: Int,
    picturesCount: Int,
    recentSearches: List<String>,
    newestDocs: List<VaultDocument>,
    lang: com.amar.vault.ui.AppLanguage,
    onSearch: (String) -> Unit,
    onDeleteSearch: (String) -> Unit,
    onAddDocuments: () -> Unit,
    onOpenDocument: (VaultDocument) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        if (documentsCount == 0 && picturesCount == 0) {
            // Empty Vault state
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "🗄️", fontSize = 40.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = VaultStrings.emptyVaultTitle(lang),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = VaultStrings.emptyVaultHint(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onAddDocuments,
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(VaultStrings.addDocuments(lang))
                        }
                    }
                }
            }
        } else {
            // Summary Chip
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = VaultStrings.vaultGlance(lang, documentsCount, picturesCount),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Recent Searches
            if (recentSearches.isNotEmpty()) {
                item {
                    Text(
                        text = VaultStrings.recentSearches(lang).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(recentSearches.take(8)) { query ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.clickable { onSearch(query) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = query,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Delete search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { onDeleteSearch(query) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Recent Documents
            if (newestDocs.isNotEmpty()) {
                item {
                    Text(
                        text = "RECENT DOCUMENTS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        newestDocs.forEach { doc ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenDocument(doc) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "📄", fontSize = 18.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = doc.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (doc.pageCount != null) "${doc.pageCount} pages" else "Document",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsContent(
    queryText: String,
    isLoading: Boolean,
    directHits: List<SearchHit>,
    partialHits: List<SearchHit>,
    photoMatches: List<VaultItem>,
    partialOpen: Boolean,
    similarSpellingsOnly: Boolean,
    lang: com.amar.vault.ui.AppLanguage,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onShowPartial: () -> Unit,
    onResultClick: (SearchHit) -> Unit,
    onResultLongClick: (SearchHit) -> Unit,
    onPhotoClick: (VaultItem) -> Unit
) {
    if (isLoading && directHits.isEmpty() && photoMatches.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (directHits.isEmpty() && partialHits.isEmpty() && photoMatches.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = VaultStrings.noResultsTitle(lang, queryText.trim()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = VaultStrings.noResultsHint(lang),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Photos match section ("Show, don't list")
        if (photoMatches.isNotEmpty()) {
            item(key = "photos_section") {
                Text(
                    text = VaultStrings.photosSection(lang, photoMatches.size).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(photoMatches) { photo ->
                        PhotoMatchThumbnail(photo = photo, onClick = { onPhotoClick(photo) })
                    }
                }
            }
        }

        // Direct Documents match section
        if (directHits.isNotEmpty()) {
            item(key = "documents_header") {
                Text(
                    text = VaultStrings.docsSection(lang, directHits.size).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }

            items(directHits, key = { it.row.id }) { hit ->
                DocumentHitCard(
                    hit = hit,
                    query = queryText,
                    onClick = { onResultClick(hit) },
                    onLongClick = { onResultLongClick(hit) }
                )
            }
        }

        // Partial / Typo Matches Accordion
        if (partialHits.isNotEmpty() && !partialOpen) {
            item(key = "partial_toggle") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onShowPartial)
                ) {
                    Text(
                        text = "▾ " + VaultStrings.partialMatches(lang, partialHits.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        if (partialOpen && partialHits.isNotEmpty()) {
            item(key = "partial_note") {
                Text(
                    text = "Found inside other words or with similar spelling:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(partialHits, key = { "partial_" + it.row.id }) { hit ->
                DocumentHitCard(
                    hit = hit,
                    query = queryText,
                    onClick = { onResultClick(hit) },
                    onLongClick = { onResultLongClick(hit) }
                )
            }
        }
    }
}

@Composable
private fun PhotoMatchThumbnail(
    photo: VaultItem,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .size(100.dp)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(photo.uri)
                .crossfade(true)
                .build(),
            contentDescription = "Matched Photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun DocumentHitCard(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val item = hit.row
    val title = item.title ?: item.sourceFile.substringAfterLast('/').ifBlank { "Document" }
    val isPdf = ContentSpecies.classify(item) == ContentSpecies.PDF

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(text = if (isPdf) "📄" else "🖼️", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (hit.page != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "Page ${hit.page}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (!hit.excerpt.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                val annotatedExcerpt = highlightQueryWords(hit.excerpt, query)
                Text(
                    text = annotatedExcerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hit.page != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Open on page ${hit.page} →",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private fun highlightQueryWords(text: String, query: String): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        val words = queryWordsOf(query)
        for (range in MatchMarks.ranges(text, words)) {
            addStyle(
                style = SpanStyle(
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color(0xFF0B57D0)
                ),
                start = range.first,
                end = range.last + 1
            )
        }
    }
}

private fun openHitAtMatch(
    context: Context,
    hit: SearchHit,
    query: String,
    onFallback: (String) -> Unit
) {
    val item = hit.row
    if (item.itemType.isImage) {
        AmarImageViewerActivity.open(context, item)
        return
    }

    if (ContentSpecies.classify(item) == ContentSpecies.PDF) {
        val uri = ShareContentUri.resolve(context, item.uri)
        if (uri != null) {
            PdfViewerActivity.open(
                context = context,
                uri = uri,
                page = hit.page ?: 0,
                searchQuery = if (hit.page != null) hit.similarWord ?: query.trim() else "",
                fileName = item.title ?: item.sourceFile,
                matchText = hit.excerpt.orEmpty()
            )
            return
        }
    }
    onFallback(item.id)
}

private fun openDocumentAtStart(context: Context, doc: VaultDocument) {
    val uri = runCatching { android.net.Uri.parse(doc.uri) }.getOrNull()
    if (uri != null && doc.name.endsWith(".pdf", ignoreCase = true)) {
        PdfViewerActivity.open(
            context = context,
            uri = uri,
            page = 0,
            searchQuery = "",
            fileName = doc.name,
            matchText = ""
        )
    }
}
