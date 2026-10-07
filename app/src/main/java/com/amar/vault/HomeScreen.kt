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
import androidx.compose.runtime.Composable
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
                IconButton(onClick = onDocumentsClick) {
                    Icon(
                        imageVector = DocumentIcon,
                        contentDescription = "Import documents",
                        tint = CharcoalSoft,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onAgenticClick) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Ask Vault",
                        tint = CharcoalSoft,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onTimelineClick) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Timeline",
                        tint = CharcoalSoft,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = CharcoalSoft,
                        modifier = Modifier.size(22.dp)
                    )
                }
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 36.dp),
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
