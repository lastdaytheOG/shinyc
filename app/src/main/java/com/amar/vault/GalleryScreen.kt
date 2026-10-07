package com.amar.vault

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val stashResults by viewModel.results.collectAsStateWithLifecycle()
    val results = remember(stashResults) { stashResults.map { it.toVaultItem() } }
    val allItems by viewModel.allItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isSearchLoading.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableIntStateOf(0) }

    // In-app photo viewer state
    var viewerPhoto by remember { mutableStateOf<VaultItem?>(null) }
    var viewerList by remember { mutableStateOf<List<VaultItem>>(emptyList()) }

    val context = LocalContext.current

    // Photos only (no document chunks)
    val photos = remember(allItems) {
        allItems.filter { !it.isDocumentPiece }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Screenshots") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Documents") })
            }

            if (selectedTab == 1) {
                DocumentPickerScreen()
                return@Column
            }

            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                placeholder = { Text("Search your vault...") },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                trailingIcon = {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            )

            if (query.isNotBlank()) {
                SearchResults(results, query) { item, q ->
                    openItemOrViewer(context, item, q, photos) { photo, list ->
                        viewerPhoto = photo
                        viewerList = list
                    }
                }
            } else {
                TimelineGallery(photos) { photo ->
                    viewerPhoto = photo
                    viewerList = photos
                }
            }
        }

        // In-app photo viewer overlay
        if (viewerPhoto != null) {
            PhotoViewer(
                photo = viewerPhoto!!,
                photos = viewerList.ifEmpty { photos },
                onClose = { viewerPhoto = null },
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Search results
// ═══════════════════════════════════════════════════════════════════════

private sealed interface SearchEntry {
    data class Single(val item: VaultItem) : SearchEntry
    data class DocGroup(val chunks: List<VaultItem>) : SearchEntry
}

@Composable
private fun SearchResults(results: List<VaultItem>, query: String, onItemClick: (VaultItem, String) -> Unit) {
    if (results.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No results found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val grouped = remember(results) {
        val docs = mutableMapOf<String, MutableList<VaultItem>>()
        val singles = mutableListOf<VaultItem>()

        results.forEach { item ->
            val isDoc = item.isDocumentPiece && !item.sourceFile.isNullOrBlank()
            if (isDoc) {
                docs.getOrPut(item.sourceFile!!) { mutableListOf() }.add(item)
            } else {
                singles.add(item)
            }
        }

        docs.values.forEach { chunks -> chunks.sortBy { it.pageNum ?: 0 } }

        val entries = mutableListOf<SearchEntry>()
        val docEntries = docs.entries.map { (_, chunks) -> SearchEntry.DocGroup(chunks) to chunks.maxOf { it.timestamp } }
        val singleEntries = singles.map { SearchEntry.Single(it) to it.timestamp }

        (docEntries + singleEntries)
            .sortedByDescending { it.second }
            .mapTo(entries) { it.first }

        entries
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(grouped.size, key = { idx ->
            when (val e = grouped[idx]) {
                is SearchEntry.Single -> e.item.id
                is SearchEntry.DocGroup -> "group_${e.chunks.first().sourceFile}"
            }
        }) { index ->
            when (val entry = grouped[index]) {
                is SearchEntry.Single -> SearchResultCard(entry.item, query, onItemClick)
                is SearchEntry.DocGroup -> DocumentGroupCard(entry.chunks, query, onItemClick)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Document group card
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun DocumentGroupCard(chunks: List<VaultItem>, query: String, onItemClick: (VaultItem, String) -> Unit) {
    val context = LocalContext.current
    val first = chunks.first()
    var expanded by remember { mutableStateOf(chunks.size <= 3) }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileTypeBadge(first.itemType)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        first.sourceFile ?: "Unknown file",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (first.itemType == ItemType.PDF) "${chunks.size} matching pages found"
                        else "${chunks.size} matching sections found",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(formatTimestamp(first.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            val visible = if (expanded) chunks else chunks.take(2)
            visible.forEach { chunk ->
                Spacer(Modifier.height(10.dp))
                ChunkMatchRow(chunk, query) { onItemClick(chunk, query) }
            }

            if (chunks.size > 3 && !expanded) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Show ${chunks.size - 2} more matches")
                }
            } else if (chunks.size > 3 && expanded) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Show less")
                }
            }
        }
    }
}

@Composable
private fun ChunkMatchRow(chunk: VaultItem, query: String, onClick: () -> Unit) {
    val cleanText = chunk.ocrText.trim()
    val matchCount = countMatches(cleanText, query)
    val snippet = buildMatchSnippet(cleanText, query, 160)
    val highlighted = highlightMatches(snippet, query)

    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    if (chunk.itemType == ItemType.PDF) "P${chunk.pageNum ?: 1}" else "#${(chunk.pageNum ?: 0) + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(highlighted, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (matchCount > 1) {
                    Spacer(Modifier.height(3.dp))
                    Text("$matchCount matches in this section", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Search result card — screenshots + lone doc chunks
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SearchResultCard(item: VaultItem, query: String, onItemClick: (VaultItem, String) -> Unit) {
    val context = LocalContext.current
    val isDocument = item.isDocumentPiece

    val actions = remember(item.ocrText, item.qrPayload) {
        NerActionEngine.detect(item.ocrText.trim(), QrPayloads.split(item.qrPayload))
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onItemClick(item, query) },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Document header
            if (isDocument) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    FileTypeBadge(item.itemType)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.sourceFile ?: "Unknown file", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (item.itemType == ItemType.PDF) "Page ${item.pageNum ?: 1}" else "Chunk ${(item.pageNum ?: 0) + 1}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(formatTimestamp(item.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(bottom = 10.dp))
            }

            // Content
            Row(verticalAlignment = Alignment.Top) {
                if (!isDocument && item.uri.startsWith("content://")) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(item.uri).allowHardware(true).crossfade(true).build(),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    val cleanText = item.ocrText.trim()
                    val snippet = buildMatchSnippet(cleanText, query, 180)
                    val highlighted = highlightMatches(snippet, query)
                    Text(highlighted, style = MaterialTheme.typography.bodySmall, maxLines = if (isDocument) 4 else 3, lineHeight = 18.sp, overflow = TextOverflow.Ellipsis)
                    if (!isDocument) {
                        Spacer(Modifier.height(4.dp))
                        Text(formatTimestamp(item.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Action chips — ALL actions shown, wrapped in rows of 3
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                val chunkedActions = actions.chunked(3)
                chunkedActions.forEach { rowActions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        rowActions.forEach { action ->
                            AssistChip(
                                onClick = { runCatching { context.startActivity(action.intent) } },
                                label = { Text("${action.label}: ${action.value.take(16)}", fontSize = 10.sp, maxLines = 1) }
                            )
                        }
                    }
                    if (chunkedActions.size > 1) Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// File type badge
// ═══════════════════════════════════════════════════════════════════════

private data class BadgeStyle(val label: String, val colorIndex: Int)

private val BADGE_STYLES = mapOf(
    ItemType.PDF to BadgeStyle("PDF", 2),
    ItemType.WORD to BadgeStyle("DOCX", 0),
    ItemType.EXCEL to BadgeStyle("XLSX", 1),
    ItemType.EPUB to BadgeStyle("EPUB", 3)
)

@Composable
private fun FileTypeBadge(itemType: ItemType) {
    val badge = BADGE_STYLES[itemType] ?: BadgeStyle(itemType.stored.uppercase(), 0)
    val containerColor = when (badge.colorIndex) {
        0 -> MaterialTheme.colorScheme.primaryContainer
        1 -> MaterialTheme.colorScheme.tertiaryContainer
        2 -> MaterialTheme.colorScheme.errorContainer
        3 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = when (badge.colorIndex) {
        0 -> MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.onTertiaryContainer
        2 -> MaterialTheme.colorScheme.onErrorContainer
        3 -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(containerColor).padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(badge.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = contentColor, letterSpacing = 0.5.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Timeline gallery
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun TimelineGallery(items: List<VaultItem>, onPhotoClick: (VaultItem) -> Unit) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Your vault is empty", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Text("Take a screenshot to get started", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val grouped = remember(items) {
        items.groupBy { item ->
            val cal = Calendar.getInstance().apply { timeInMillis = item.timestamp }
            val today = Calendar.getInstance()
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            when {
                isSameDay(cal, today) -> "Today"
                isSameDay(cal, yesterday) -> "Yesterday"
                else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(item.timestamp))
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        grouped.forEach { (dateLabel, dayItems) ->
            item(key = "header_$dateLabel", span = { GridItemSpan(maxLineSpan) }) {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.background) {
                    Text(dateLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                }
            }
            items(dayItems, key = { it.id }) { item -> ThumbnailCard(item) { onPhotoClick(item) } }
        }
    }
}

@Composable
private fun ThumbnailCard(item: VaultItem, onClick: () -> Unit = {}) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(item.uri).allowHardware(true).crossfade(true).memoryCachePolicy(CachePolicy.ENABLED).build(),
        contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
    )
}

// ═══════════════════════════════════════════════════════════════════════
// Utilities
// ═══════════════════════════════════════════════════════════════════════

/**
 * Routes item clicks: images → in-app PhotoViewer, PDFs → PdfViewerActivity,
 * documents → external viewer. No more "Open with" dialog for photos.
 */
private fun openItemOrViewer(
    context: android.content.Context,
    item: VaultItem,
    query: String = "",
    allPhotos: List<VaultItem>,
    showViewer: (VaultItem, List<VaultItem>) -> Unit,
) {
    if (!item.isDocumentPiece) {
        // Image/screenshot → open in-app viewer
        showViewer(item, allPhotos)
        return
    }

    if (item.itemType == ItemType.PDF) {
        PdfViewerActivity.open(
            context = context, uri = Uri.parse(item.uri),
            page = item.pageNum ?: 1, searchQuery = query,
            fileName = item.sourceFile ?: ""
        )
        return
    }

    // Word/Excel/EPUB → external viewer
    val mimeType = when (item.itemType) {
        ItemType.WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ItemType.EXCEL -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        ItemType.EPUB -> "application/epub+zip"
        else -> "image/*"
    }

    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(item.uri), mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.uri)).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }
}

@Composable
private fun highlightMatches(text: String, query: String): AnnotatedString {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    val highlightTextColor = MaterialTheme.colorScheme.onPrimaryContainer

    return remember(text, query) {
        val words = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.isEmpty()) return@remember AnnotatedString(text)

        val builder = AnnotatedString.Builder(text)
        val lowerText = text.lowercase()

        words.forEach { word ->
            val lw = word.lowercase()
            var pos = 0
            while (true) {
                val idx = lowerText.indexOf(lw, pos)
                if (idx == -1) break
                builder.addStyle(SpanStyle(background = highlightColor, color = highlightTextColor, fontWeight = FontWeight.Medium), idx, idx + lw.length)
                pos = idx + lw.length
            }
        }
        builder.toAnnotatedString()
    }
}

private fun buildMatchSnippet(text: String, query: String, maxChars: Int = 160): String {
    if (text.length <= maxChars) return text
    val words = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }
    if (words.isEmpty()) return text.take(maxChars) + "…"

    val lt = text.lowercase()
    val firstIdx = words.mapNotNull { w -> lt.indexOf(w.lowercase()).takeIf { it >= 0 } }.minOrNull()
        ?: return text.take(maxChars) + "…"

    val half = maxChars / 2
    val s = (firstIdx - half).coerceAtLeast(0)
    val e = (s + maxChars).coerceAtMost(text.length)
    val adjS = if (e == text.length) (e - maxChars).coerceAtLeast(0) else s

    val prefix = if (adjS > 0) "…" else ""
    val suffix = if (e < text.length) "…" else ""
    return "$prefix${text.substring(adjS, e)}$suffix"
}

private fun countMatches(text: String, query: String): Int {
    val lt = text.lowercase()
    return query.trim().split(Regex("\\s+")).filter { it.length >= 2 }.sumOf { w ->
        val lw = w.lowercase()
        var c = 0; var p = 0
        while (true) { val i = lt.indexOf(lw, p); if (i == -1) break; c++; p = i + lw.length }
        c
    }
}

private fun isSameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))