package com.amar.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.amar.vault.ui.theme.*
import com.amar.vault.bounceClick
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow

private val BookmarkIcon = ImageVector.Builder(
    name = "Bookmark",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    fill = SolidColor(Color.Black)
) {
    moveTo(17f, 3f)
    lineTo(7f, 3f)
    curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
    lineTo(5f, 21f)
    lineTo(12f, 18f)
    lineTo(19f, 21f)
    lineTo(19f, 5f)
    curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
    close()
}.build()

/** A page with a folded corner and two lines of text. */
private val DocumentIcon = ImageVector.Builder(
    name = "Document",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    fill = SolidColor(Color.Black)
) {
    moveTo(14f, 2f)
    lineTo(6f, 2f)
    curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
    lineTo(4f, 20f)
    curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
    lineTo(18f, 22f)
    curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
    lineTo(20f, 8f)
    close()
    // Cut-outs, wound the other way: the two text lines and the corner fold.
    moveTo(16f, 18f)
    lineTo(8f, 18f)
    lineTo(8f, 16f)
    lineTo(16f, 16f)
    close()
    moveTo(16f, 14f)
    lineTo(8f, 14f)
    lineTo(8f, 12f)
    lineTo(16f, 12f)
    close()
    moveTo(13f, 9f)
    lineTo(13f, 3.5f)
    lineTo(18.5f, 9f)
    close()
}.build()

@Composable
fun HomeScreen(
    stashItems: List<StashItemWithVaultItem>,
    isSyncing: Boolean,
    onSearchTriggerClick: () -> Unit,
    onSavedClick: () -> Unit,
    onRecentItemClick: (StashItemWithVaultItem) -> Unit,
    onAgenticClick: () -> Unit,
    onPhotosClick: () -> Unit,
    onDocumentsClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    onTimelineClick: () -> Unit = {},
    /** Opens search with these words already typed. */
    onSearch: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(54.dp))

        // Header: Amar Vault + Ask Vault + Settings
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Amar Vault",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = WarmBrownDark,
                letterSpacing = 0.5.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // The one place a PDF, Word, Excel or EPUB file is added from inside the app.
                TopAction(icon = DocumentIcon, label = "Add", description = "Import documents", onClick = onDocumentsClick)
                TopAction(icon = Icons.Default.Send, label = "Ask", description = "Ask Vault", onClick = onAgenticClick)
                TopAction(icon = Icons.Default.DateRange, label = "Timeline", description = "Timeline", onClick = onTimelineClick)
                TopAction(icon = Icons.Default.Settings, label = "Settings", description = "Settings", onClick = onSettingsClick)
            }
        }

        Spacer(Modifier.height(20.dp))

        // Large Search Bar (Largest visual element)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CreamLight)
                .bounceClick { onSearchTriggerClick() }
                .border(1.dp, CreamDark, RoundedCornerShape(16.dp))
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = WarmBrown,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "Search screenshots, receipts, notes...",
                    color = WarmBrown,
                    fontSize = 16.sp
                )
            }
        }

        HomeGlance(
            modifier = Modifier.weight(1f),
            onSearch = onSearch,
            onDocumentsClick = onDocumentsClick,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 36.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .bounceClick { onSavedClick() }
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(4.dp, shape = CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(WarmBrown, WarmBrownDark)
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = BookmarkIcon,
                        contentDescription = "Stash",
                        tint = Cream,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Stash",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WarmBrownDark
                )
            }
        }
    }
}

/** One of the actions at the top of Home: its icon, and under it the word for what it does. */
@Composable
private fun TopAction(icon: ImageVector, label: String, description: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = CharcoalSoft, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(text = label, fontSize = 10.sp, color = WarmBrownDark)
    }
}

/** What Home says about the vault, in words. */
internal object HomeWords {
    fun summary(documents: Int, pictures: Int): String {
        fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
        return when {
            documents == 0 && pictures == 0 -> "Nothing in your vault yet"
            pictures == 0 -> count(documents, "document", "documents") + " you can search"
            documents == 0 -> count(pictures, "picture", "pictures") + " you can search"
            else -> count(documents, "document", "documents") + " and " + count(pictures, "picture", "pictures") + " you can search"
        }
    }

    const val EMPTY_HINT = "Add a PDF, Word, Excel or EPUB file and every page of it can be searched. " +
        "Screenshots and photos are read on their own."

    fun added(addedAt: Long, pages: Int?, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - addedAt) / 60_000
        val whenAdded = when {
            minutes < 1 -> "Added just now"
            minutes < 60 -> "Added $minutes min ago"
            minutes < 24 * 60 -> "Added ${minutes / 60} hr ago"
            minutes < 2 * 24 * 60 -> "Added yesterday"
            else -> "Added ${minutes / (24 * 60)} days ago"
        }
        return if (pages == null) whenAdded else "$whenAdded · " + (if (pages == 1) "1 page" else "$pages pages")
    }
}

/**
 * The middle of Home: what the vault holds, what is being read, the last things searched for
 * and the last documents added. It used to be empty — a search box above a blank page — and
 * nothing said whether the vault had anything in it.
 */
@Composable
private fun HomeGlance(modifier: Modifier, onSearch: (String) -> Unit, onDocumentsClick: () -> Unit) {
    val context = LocalContext.current
    val db = remember { VaultDatabase.get(context) }
    val documents by remember { db.vaultDocumentDao().observeCount() }.collectAsState(initial = -1)
    val pictures by remember { db.vaultDao().observePictureCount() }.collectAsState(initial = -1)
    val newest by remember { db.vaultDocumentDao().observeNewest(4) }.collectAsState(initial = emptyList())
    val imports by remember { db.documentImportDao().observeAll() }.collectAsState(initial = emptyList())
    val reading = imports.filter { !it.isFinished }
    val searches = remember { com.amar.vault.search.core.HistoryManager.getRecentSearches().take(6) }

    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(14.dp))
        // Until the counts are in, nothing is said: "nothing yet" must not flash on a full vault.
        if (documents >= 0 && pictures >= 0) {
            Text(text = HomeWords.summary(documents, pictures), color = WarmBrownDark, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp))
        }

        if (reading.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val first = reading.first()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CreamLight)
                    .border(1.dp, CreamDark, RoundedCornerShape(12.dp))
                    .clickable(onClick = onDocumentsClick)
                    .padding(14.dp)
            ) {
                Text(
                    text = if (reading.size == 1) first.name else "${first.name} and ${reading.size - 1} more",
                    color = CharcoalSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(text = com.amar.vault.indexing.ImportWords.progress(first), color = WarmBrownDark, fontSize = 12.sp)
            }
        }

        if (documents == 0 && pictures == 0) {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CreamLight)
                    .border(1.dp, CreamDark, RoundedCornerShape(16.dp))
                    .padding(18.dp)
            ) {
                Text(HomeWords.EMPTY_HINT, color = WarmBrownDark, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Add documents",
                    color = Cream, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(CharcoalSoft)
                        .clickable(onClick = onDocumentsClick)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }

        if (searches.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            HomeHeading("Recent searches")
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                searches.forEach { words ->
                    Text(
                        text = words,
                        color = CharcoalSoft, fontSize = 13.sp, maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(CreamLight)
                            .border(1.dp, CreamDark, RoundedCornerShape(16.dp))
                            .clickable { onSearch(words) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (newest.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            HomeHeading("Recently added")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                newest.forEach { document ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CreamLight)
                            .border(1.dp, CreamDark, RoundedCornerShape(12.dp))
                            .clickable { openDocument(context, document) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(PdfColor))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(document.name, color = CharcoalSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(HomeWords.added(document.addedAt, document.pageCount), color = WarmBrown, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun HomeHeading(text: String) {
    Text(
        text = text.uppercase(), color = WarmBrown, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** Opens a document from Home: a PDF in the app's viewer at the page it was left on, anything else in its own app. */
private fun openDocument(context: android.content.Context, document: VaultDocument) {
    val address = com.amar.vault.indexing.DocumentRelink.currentAddress(document.uri)
    if (document.itemType == ItemType.PDF) {
        val uri = com.amar.vault.share.open.ShareContentUri.resolve(context, address) ?: return
        PdfViewerActivity.open(context = context, uri = uri, page = 0, fileName = document.name)
    } else {
        ContentOpenManager.open(
            context,
            VaultItem(
                id = document.id, uri = address, ocrText = "", lang = "en", itemType = document.itemType,
                sourceFile = document.name, timestamp = document.addedAt, title = document.name,
            ),
        )
    }
}

@Composable
private fun SavedItemRowCard(item: StashItemWithVaultItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(140.dp)
            .height(130.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CreamLight),
        border = BorderStroke(1.dp, CreamDark)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFFE5DCD0)),
                contentAlignment = Alignment.Center
            ) {
                if (item.itemType.isImage) {
                    val painter = rememberAsyncImagePainter(model = item.uri)
                    Image(
                        painter = painter,
                        contentDescription = "Saved item",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val emoji = when (item.itemType) {
                        ItemType.LINK -> when (LinkSite.of(item.uri)) {
                            LinkSite.YOUTUBE -> "🎥"
                            LinkSite.REDDIT -> "🤖"
                            LinkSite.OTHER -> "🔗"
                        }
                        ItemType.TEXT -> "📝"
                        ItemType.PDF -> "📄"
                        else -> "📁"
                    }
                    Text(text = emoji, fontSize = 28.sp)
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                val displayTitle = item.title ?: item.sourceFile.takeIf { it.isNotBlank() } ?: when (item.itemType) {
                    ItemType.SCREENSHOT -> "Screenshot"
                    ItemType.PHOTO -> "Photo"
                    ItemType.LINK -> when (LinkSite.of(item.uri)) {
                        LinkSite.YOUTUBE -> "YouTube"
                        LinkSite.REDDIT -> "Reddit"
                        LinkSite.OTHER -> "Document"
                    }
                    ItemType.TEXT -> "Text Note"
                    ItemType.PDF -> "PDF Document"
                    else -> "Document"
                }
                Text(
                    text = displayTitle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CharcoalSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val appLabel = if (item.sourceApp.isNotBlank()) "from ${item.sourceApp}" else formatRelativeTime(item.savedAt)
                Text(
                    text = appLabel,
                    fontSize = 10.sp,
                    color = WarmBrown,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        days > 0 -> "$days days ago"
        hours > 0 -> "$hours hr ago"
        minutes > 0 -> "$minutes min ago"
        else -> "Just now"
    }
}
