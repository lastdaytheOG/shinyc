package com.amar.vault.ui.components.saved

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.RecentActivityKind
import com.amar.vault.bounceClick
import com.amar.vault.ui.renderengine.components.PlatformBadge
import com.amar.vault.ui.renderengine.components.SavedThumbnail
import com.amar.vault.ui.renderengine.models.RichSavedItem
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.WarmBrownDark

/**
 * Saved-only recent-activity rail with four feeds (Saved / Opened / Moved /
 * Favorited). All feeds are derived in-memory from the loaded list plus the
 * DataStore trails — no global activity system.
 */
@Composable
fun RecentActivityRail(
    items: List<RichSavedItem>,
    recentlyOpened: List<String>,
    recentlyMoved: List<String>,
    recentlyFavorited: List<String>,
    onOpen: (RichSavedItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    var kind by remember { mutableStateOf(RecentActivityKind.SAVED) }
    val byId = remember(items) { items.associateBy { it.id } }

    val feed: List<RichSavedItem> = remember(kind, items, recentlyOpened, recentlyMoved, recentlyFavorited) {
        when (kind) {
            RecentActivityKind.SAVED -> items.sortedByDescending { it.savedAtMillis }.take(12)
            RecentActivityKind.OPENED -> recentlyOpened.mapNotNull { byId[it] }.take(12)
            RecentActivityKind.MOVED -> recentlyMoved.mapNotNull { byId[it] }.take(12)
            RecentActivityKind.FAVORITED -> {
                val trail = recentlyFavorited.mapNotNull { byId[it] }
                val favs = items.filter { it.isFavorite && it.id !in recentlyFavorited }
                (trail + favs).take(12)
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RecentActivityKind.values().forEach { k ->
                val selected = k == kind
                val bg by animateColorAsState(if (selected) CharcoalSoft else CreamLight, label = "tabBg")
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .bounceClick { kind = k }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(k.icon, fontSize = 12.sp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        k.label.removePrefix("Recently "),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) Color.White else CharcoalSoft
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (feed.isEmpty()) {
            Text(
                "Nothing here yet.",
                fontSize = 13.sp,
                color = WarmBrownDark,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                feed.forEach { item -> RecentCard(item, onClick = { onOpen(item) }) }
            }
        }
    }
}

@Composable
private fun RecentCard(item: RichSavedItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(18.dp))
            .bounceClick { onClick() }
    ) {
        // Larger card (#7) with a real thumbnail + platform badge, so the origin of
        // each item reads instantly instead of a small gray square.
        SavedThumbnail(
            model = item.thumbnail,
            style = item.style,
            fallbackIcon = com.amar.vault.ui.renderengine.core.PlatformStyles.iconFor(item.contentType),
            aspectRatio = 4f / 5f,
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
        ) {
            val label = com.amar.vault.ui.renderengine.core.PlatformStyles.labelFor(item.contentType)
                ?: item.sourceLabel
            if (!label.isNullOrBlank()) {
                PlatformBadge(
                    label = label,
                    style = item.style,
                    icon = com.amar.vault.ui.renderengine.core.PlatformStyles.iconFor(item.contentType),
                    onDark = true,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = CharcoalSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 15.sp
        )
    }
}
