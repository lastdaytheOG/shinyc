@file:OptIn(ExperimentalFoundationApi::class)

package com.amar.vault.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amar.vault.AmarImageViewerActivity
import com.amar.vault.ContentSpecies
import com.amar.vault.MatchMarks
import com.amar.vault.PdfViewerActivity
import com.amar.vault.SearchFilter
import com.amar.vault.SearchHit
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import com.amar.vault.queryWordsOf
import com.amar.vault.share.open.ShareContentUri
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import com.amar.vault.ui.components.GroupedRow
import com.amar.vault.ui.components.GroupedSection
import com.amar.vault.ui.components.IconBadge
import com.amar.vault.ui.components.LargeTitle
import com.amar.vault.ui.components.SearchField
import com.amar.vault.ui.components.SegmentedControl
import com.amar.vault.ui.components.Tints
import com.amar.vault.ui.theme.LocalReducedMotion

@Composable
fun SearchScreen(
    viewModel: com.amar.vault.SearchViewModel,
    onNavigateToDocuments: () -> Unit = {},
    onOpenItemDetail: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lang = LocalAppLanguage.current
    val reducedMotion = LocalReducedMotion.current

    val queryText by viewModel.query.collectAsState()
    val isSearchLoading by viewModel.isSearchLoading.collectAsState()
    val searchHits by viewModel.searchHits.collectAsState()
    val activeFilter by viewModel.activeFilter.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val similarSpellingsOnly by viewModel.similarSpellingsOnly.collectAsState()
    val understood by viewModel.understood.collectAsState()
    val readingNote by viewModel.readingNote.collectAsState()
    val photoMatches by viewModel.photoMatches.collectAsState()
    val possiblePhotoMatches by viewModel.possiblePhotoMatches.collectAsState()
    val meaningMatches by viewModel.meaningMatches.collectAsState()
    val meaningFirst by viewModel.meaningFirst.collectAsState()
    val meaningCoverage by viewModel.meaningCoverage.collectAsState()

    val db = remember { VaultDatabase.get(context) }
    val documentsCount by remember { db.vaultDocumentDao().observeCount() }.collectAsState(initial = 0)
    val picturesCount by remember { db.vaultDao().observePictureCount() }.collectAsState(initial = 0)
    val newestDocs by remember { db.vaultDocumentDao().observeNewest(4) }.collectAsState(initial = emptyList())
    val imports by remember { db.documentImportDao().observeAll() }.collectAsState(initial = emptyList())
    val readingImports = imports.filter { !it.isFinished && !it.waitsForCharger }

    var toRemove by remember { mutableStateOf<VaultItem?>(null) }
    var allPhotos by remember(queryText, activeFilter) { mutableStateOf<List<VaultItem>?>(null) }
    var showPartial by remember(queryText) { mutableStateOf(false) }

    val (directHits, partialHits) = remember(searchHits, queryText, similarSpellingsOnly) {
        if (similarSpellingsOnly) emptyList<SearchHit>() to searchHits
        else {
            val words = com.amar.vault.ResultWording.contentWords(queryText)
            searchHits.partition { it.filedUnder != null || com.amar.vault.ResultWording.saysAWord(it, words) }
        }
    }

    val partialOpen = showPartial || (directHits.isEmpty() && photoMatches.isEmpty() && possiblePhotoMatches.isEmpty() && meaningMatches.isEmpty() && !similarSpellingsOnly)
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        viewModel.warmUpPhotoSearch()
        viewModel.refreshSearch()
    }

    val shownPhotos = allPhotos
    if (shownPhotos != null) {
        AllPhotosScreen(
            photos = shownPhotos,
            lang = lang,
            backLabel = VaultStrings.searchNav(lang),
            reducedMotion = reducedMotion,
            onBack = { allPhotos = null },
            onOpen = { index ->
                viewModel.recordSearch(queryText)
                viewModel.recordOpen(queryText, shownPhotos[index])
                AmarImageViewerActivity.open(context, shownPhotos, index)
            },
            onLongClick = { toRemove = it }
        )
    } else Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LargeTitle(VaultStrings.searchNav(lang))

        SearchField(
            query = queryText,
            placeholder = VaultStrings.searchPlaceholder(lang),
            clearDescription = VaultStrings.clearSearch(lang),
            onQueryChange = { viewModel.updateQuery(it) },
            onClear = { viewModel.updateQuery("") },
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        val filters = listOf(SearchFilter.ALL, SearchFilter.IMAGES, SearchFilter.DOCUMENTS)
        SegmentedControl(
            options = listOf(VaultStrings.filterAll(lang), VaultStrings.filterPhotos(lang), VaultStrings.filterDocs(lang)),
            selected = filters.indexOf(activeFilter).coerceAtLeast(0),
            onSelect = { viewModel.updateFilter(filters[it]) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
        )

        if (understood.isNotEmpty()) {
            UnderstoodRow(understood = understood, lang = lang, onTakeBack = { viewModel.takeBack(it) })
        }

        if (readingImports.isNotEmpty()) {
            val first = readingImports.first()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = onNavigateToDocuments,
                        role = Role.Button
                    ),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (reducedMotion) {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (readingImports.size == 1) VaultStrings.readingNow(lang, first.name)
                        else VaultStrings.readingNowMore(lang, first.name, readingImports.size - 1),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

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
                onOpenDocument = { doc -> openDocumentAtStart(context, doc) }
            )
        } else {
            SearchResultsContent(
                queryText = queryText,
                isLoading = isSearchLoading,
                directHits = directHits,
                partialHits = partialHits,
                photoMatches = photoMatches,
                possiblePhotoMatches = possiblePhotoMatches,
                meaningMatches = meaningMatches,
                meaningFirst = meaningFirst,
                meaningCoverage = meaningCoverage,
                partialOpen = partialOpen,
                similarSpellingsOnly = similarSpellingsOnly,
                readingNote = readingNote,
                lang = lang,
                reducedMotion = reducedMotion,
                listState = listState,
                onShowPartial = { showPartial = true },
                onResultClick = { hit ->
                    viewModel.recordSearch(queryText)
                    viewModel.recordOpen(queryText, hit.row)
                    openHitAtMatch(context, hit, queryText, onOpenItemDetail)
                },
                onResultLongClick = { hit -> toRemove = hit.row },
                activeFilter = activeFilter,
                onOpenPhotos = { photos, index ->
                    viewModel.recordSearch(queryText)
                    viewModel.recordOpen(queryText, photos[index])
                    AmarImageViewerActivity.open(context, photos, index)
                },
                onSeeAllPhotos = { allPhotos = it },
                onPhotoLongClick = { toRemove = it }
            )
        }
    }

    toRemove?.let { item ->
        val isDocument = item.parentDocumentId != null || SearchFilter.isDocument(item)
        val isPicture = item.itemType.isImage
        val name = item.title
            ?: item.sourceFile.substringAfterLast('/').ifBlank { VaultStrings.documentLabel(lang) }
        AlertDialog(
            onDismissRequest = { toRemove = null },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.FolderOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            title = {
                Text(
                    text = VaultStrings.removalTitle(lang, name),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = when {
                        isDocument -> VaultStrings.removalBodyDocument(lang)
                        isPicture -> VaultStrings.removalBodyPicture(lang)
                        else -> VaultStrings.removalBodyOther(lang)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = toRemove
                    toRemove = null
                    if (target != null) viewModel.removeFromVault(target)
                }) {
                    Text(VaultStrings.remove(lang), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { toRemove = null }) {
                    Text(VaultStrings.cancel(lang))
                }
            }
        )
    }
}

@Composable
private fun UnderstoodRow(
    understood: List<com.amar.vault.Understood>,
    lang: com.amar.vault.ui.AppLanguage,
    onTakeBack: (com.amar.vault.Understood) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        understood.forEach { reading ->
            AssistChip(
                onClick = { onTakeBack(reading) },
                label = { Text(reading.chip) },
                shape = RoundedCornerShape(50),
                border = null,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    labelColor = MaterialTheme.colorScheme.primary,
                    trailingIconContentColor = MaterialTheme.colorScheme.primary
                ),
                trailingIcon = {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = VaultStrings.takeBackReading(lang),
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        if (documentsCount == 0 && picturesCount == 0) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 32.dp, top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconBadge(Icons.Rounded.Description, Tints.Blue, size = 64)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = VaultStrings.emptyVaultTitle(lang),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = VaultStrings.emptyVaultHint(lang),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onAddDocuments, shape = RoundedCornerShape(12.dp)) {
                        Text(VaultStrings.addDocuments(lang), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CountTile(
                        icon = Icons.Rounded.Description,
                        tint = Tints.Blue,
                        count = documentsCount,
                        label = VaultStrings.docsNav(lang),
                        onClick = onAddDocuments,
                        modifier = Modifier.weight(1f)
                    )
                    CountTile(
                        icon = Icons.Rounded.PhotoLibrary,
                        tint = Tints.Purple,
                        count = picturesCount,
                        label = VaultStrings.filterPhotos(lang),
                        onClick = null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (recentSearches.isNotEmpty()) {
                item {
                    GroupedSection(header = VaultStrings.recentSearches(lang)) {
                        recentSearches.take(6).forEach { entry ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(onClick = { onSearch(entry) }, role = Role.Button)
                                    .padding(start = 16.dp, end = 4.dp)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = entry,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onDeleteSearch(entry) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = VaultStrings.deleteSearch(lang),
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (newestDocs.isNotEmpty()) {
                item {
                    GroupedSection(header = VaultStrings.recentDocuments(lang)) {
                        newestDocs.forEach { doc ->
                            GroupedRow(
                                title = doc.name,
                                subtitle = doc.pageCount?.let { VaultStrings.pagesCount(lang, it) }
                                    ?: VaultStrings.documentLabel(lang),
                                icon = Icons.Rounded.Description,
                                tint = Tints.Red,
                                onClick = { onOpenDocument(doc) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    count: Int,
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick, role = Role.Button) else Modifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, tint, size = 32)
                Spacer(Modifier.weight(1f))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
    possiblePhotoMatches: List<VaultItem>,
    meaningMatches: List<SearchHit>,
    meaningFirst: Boolean,
    meaningCoverage: Float,
    partialOpen: Boolean,
    similarSpellingsOnly: Boolean,
    readingNote: String?,
    lang: com.amar.vault.ui.AppLanguage,
    reducedMotion: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onShowPartial: () -> Unit,
    onResultClick: (SearchHit) -> Unit,
    onResultLongClick: (SearchHit) -> Unit,
    activeFilter: String,
    onOpenPhotos: (List<VaultItem>, Int) -> Unit,
    onSeeAllPhotos: (List<VaultItem>) -> Unit,
    onPhotoLongClick: (VaultItem) -> Unit
) {
    val showDocuments = activeFilter != SearchFilter.IMAGES
    val documentHits = if (showDocuments) directHits.filterNot { it.row.itemType.isImage } else emptyList()
    val photos = remember(directHits, photoMatches, activeFilter) {
        if (activeFilter == SearchFilter.DOCUMENTS) emptyList()
        else com.amar.vault.ResultWording.mixed(directHits.filter { it.row.itemType.isImage }.map { it.row }, photoMatches)
    }
    if (isLoading && documentHits.isEmpty() && photos.isEmpty() && possiblePhotoMatches.isEmpty() && partialHits.isEmpty() && meaningMatches.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            if (reducedMotion) {
                Text(
                    text = VaultStrings.searching(lang),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = VaultStrings.searching(lang),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    if (documentHits.isEmpty() && partialHits.isEmpty() && photos.isEmpty() && possiblePhotoMatches.isEmpty() && meaningMatches.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SearchOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = VaultStrings.noResultsTitle(lang, queryText.trim()),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = VaultStrings.noResultsHint(lang),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                if (meaningCoverage < 1f) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = VaultStrings.nothingYet(lang, (meaningCoverage * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (readingNote != null) {
            item(key = "reading_note") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = readingNote,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (photos.isNotEmpty()) {
            item(key = "photos_section") {
                PhotoResults(
                    photos = photos,
                    lang = lang,
                    reducedMotion = reducedMotion,
                    onOpen = { index -> onOpenPhotos(photos, index) },
                    onSeeAll = { onSeeAllPhotos(photos) },
                    onLongClick = onPhotoLongClick
                )
            }
        }

        if (possiblePhotoMatches.isNotEmpty()) {
            item(key = "possible_photos_section") {
                PossiblePhotoResults(
                    photos = possiblePhotoMatches,
                    lang = lang,
                    reducedMotion = reducedMotion,
                    onOpenPhotos = onOpenPhotos,
                    onSeeAllPhotos = onSeeAllPhotos,
                    onPhotoLongClick = onPhotoLongClick,
                )
            }
        }

        if (similarSpellingsOnly) {
            item(key = "similar_note") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = VaultStrings.similarSpellingsNote(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (meaningFirst) meaningSection(meaningMatches, queryText, lang, onResultClick, onResultLongClick)

        if (documentHits.isNotEmpty()) {
            item(key = "documents_header") {
                Text(
                    text = VaultStrings.docsSection(lang, documentHits.size),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(documentHits, key = { it.row.id }) { hit ->
                DocumentHitCard(
                    hit = hit,
                    query = queryText,
                    lang = lang,
                    onClick = { onResultClick(hit) },
                    onLongClick = { onResultLongClick(hit) }
                )
            }
        }

        if (partialHits.isNotEmpty() && !partialOpen) {
            item(key = "partial_toggle") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = onShowPartial,
                            role = Role.Button
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = VaultStrings.partialMatches(lang, partialHits.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (!meaningFirst) meaningSection(meaningMatches, queryText, lang, onResultClick, onResultLongClick)

        if (partialOpen && partialHits.isNotEmpty()) {
            if (!similarSpellingsOnly) {
                item(key = "partial_note") {
                    Text(
                        text = VaultStrings.partialMatchesNote(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(partialHits, key = { "partial_" + it.row.id }) { hit ->
                DocumentHitCard(
                    hit = hit,
                    query = queryText,
                    lang = lang,
                    onClick = { onResultClick(hit) },
                    onLongClick = { onResultLongClick(hit) }
                )
            }
        }
    }
}

private const val PHOTO_PREVIEW = 6

@Composable
private fun PossiblePhotoResults(
    photos: List<VaultItem>,
    lang: com.amar.vault.ui.AppLanguage,
    reducedMotion: Boolean,
    onOpenPhotos: (List<VaultItem>, Int) -> Unit,
    onSeeAllPhotos: (List<VaultItem>) -> Unit,
    onPhotoLongClick: (VaultItem) -> Unit,
) {
    var expanded by remember(photos) { mutableStateOf(false) }
    if (expanded) {
        PhotoResults(
            photos = photos,
            lang = lang,
            reducedMotion = reducedMotion,
            possible = true,
            onOpen = { index -> onOpenPhotos(photos, index) },
            onSeeAll = { onSeeAllPhotos(photos) },
            onLongClick = onPhotoLongClick,
        )
    } else {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 0.dp),
        ) {
            Text(
                text = VaultStrings.possiblePhotosSection(lang, photos.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PhotoResults(
    photos: List<VaultItem>,
    lang: com.amar.vault.ui.AppLanguage,
    reducedMotion: Boolean,
    possible: Boolean = false,
    onOpen: (Int) -> Unit,
    onSeeAll: () -> Unit,
    onLongClick: (VaultItem) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onSeeAll, role = Role.Button)
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = if (possible) VaultStrings.possiblePhotosSection(lang, photos.size)
                    else VaultStrings.photosSection(lang, photos.size),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = VaultStrings.seeAll(lang),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        val more = photos.size - PHOTO_PREVIEW
        photos.take(PHOTO_PREVIEW).chunked(3).forEachIndexed { row, three ->
            if (row > 0) Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                three.forEachIndexed { column, photo ->
                    val index = row * 3 + column
                    val last = index == PHOTO_PREVIEW - 1 && more > 0
                    PhotoTile(
                        photo = photo,
                        description = photo.title ?: VaultStrings.photoContentDesc(lang),
                        reducedMotion = reducedMotion,
                        modifier = Modifier.weight(1f),
                        onClick = { if (last) onSeeAll() else onOpen(index) },
                        onLongClick = { onLongClick(photo) },
                        overlay = if (last) "+${more + 1}" else null
                    )
                }
                repeat(3 - three.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PhotoTile(
    photo: VaultItem,
    description: String,
    reducedMotion: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    overlay: String? = null,
    corner: androidx.compose.ui.unit.Dp = 10.dp
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
    ) {
        com.amar.vault.ui.components.VaultPicture(
            address = photo.uri,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            crossfade = !reducedMotion,
            failedIconSize = 22.dp
        )
        if (overlay != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = overlay,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun AllPhotosScreen(
    photos: List<VaultItem>,
    lang: com.amar.vault.ui.AppLanguage,
    backLabel: String,
    reducedMotion: Boolean,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onLongClick: (VaultItem) -> Unit
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .padding(start = 4.dp, top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onBack, role = Role.Button)
                .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Text(backLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        LargeTitle(VaultStrings.photosSection(lang, photos.size))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
                PhotoTile(
                    photo = photo,
                    description = photo.title ?: VaultStrings.photoContentDesc(lang),
                    reducedMotion = reducedMotion,
                    modifier = Modifier,
                    onClick = { onOpen(index) },
                    onLongClick = { onLongClick(photo) },
                    corner = 0.dp
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocumentHitCard(
    hit: SearchHit,
    query: String,
    lang: com.amar.vault.ui.AppLanguage,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val item = hit.row
    val title = item.title
        ?: item.sourceFile.substringAfterLast('/').ifBlank { VaultStrings.documentLabel(lang) }
    val isPicture = item.itemType.isImage
    val isPdf = ContentSpecies.classify(item) == ContentSpecies.PDF

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.Button
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isPicture) {
                        com.amar.vault.ui.components.VaultPicture(
                            address = item.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            failedIconSize = 18.dp
                        )
                    } else {
                        IconBadge(Icons.Rounded.Description, if (isPdf) Tints.Red else Tints.Blue, size = 36)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (hit.page != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = VaultStrings.pageBadge(lang, hit.page!!),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (!hit.excerpt.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                val words = queryWordsOf(query) + listOfNotNull(hit.similarWord, hit.filedUnder)
                val annotatedExcerpt = highlightQueryWords(hit.excerpt, words.distinct())
                Text(
                    text = annotatedExcerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (hit.filedUnder != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = VaultStrings.filedUnderLine(lang, hit.filedUnder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }
    }
}

private fun highlightQueryWords(text: String, words: List<String>): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        for (range in MatchMarks.ranges(text, words)) {
            addStyle(
                style = SpanStyle(fontWeight = FontWeight.Bold),
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

private fun androidx.compose.foundation.lazy.LazyListScope.meaningSection(
    hits: List<SearchHit>,
    queryText: String,
    lang: com.amar.vault.ui.AppLanguage,
    onResultClick: (SearchHit) -> Unit,
    onResultLongClick: (SearchHit) -> Unit,
) {
    if (hits.isEmpty()) return
    item(key = "meaning_header") {
        Column {
            Text(
                text = VaultStrings.meaningSection(lang, hits.size),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = VaultStrings.meaningNote(lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    items(hits, key = { "meaning_" + it.row.id }) { hit ->
        DocumentHitCard(
            hit = hit,
            query = queryText,
            lang = lang,
            onClick = { onResultClick(hit) },
            onLongClick = { onResultLongClick(hit) }
        )
    }
}
