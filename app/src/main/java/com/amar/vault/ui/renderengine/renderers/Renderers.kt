package com.amar.vault.ui.renderengine.renderers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.renderengine.core.CardRenderer
import com.amar.vault.ui.renderengine.components.*
import com.amar.vault.ui.renderengine.models.BadgeInfo
import com.amar.vault.ui.renderengine.models.RichSavedItem

// ── Badge helpers ───────────────────────────────────────────────────────────
private fun RichSavedItem.platformBadges() = badges.filter { it.id == "platform" }
private fun RichSavedItem.factBadges() = badges.filter { it.id != "platform" }

@Composable
private fun BoxScope.CornerBadges(item: RichSavedItem) {
    val platform = item.platformBadges()
    val facts = item.factBadges()
    if (platform.isNotEmpty()) {
        BadgeRow(platform, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
    }
    if (item.isFavorite) {
        FavoriteDot(modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
    if (facts.isNotEmpty()) {
        BadgeRow(facts, modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
    }
}

// ── YouTube ─────────────────────────────────────────────────────────────────
class YoutubeRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = "▶",
                aspectRatio = 16f / 9f
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶", color = Color.White, fontSize = 22.sp)
                }
                CornerBadges(item)
            }
            CardMetadata(item)
        }
    }
}

// ── Instagram / TikTok / Pinterest (tall imagery) ────────────────────────────
class InstagramRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = "🎬",
                aspectRatio = 4f / 5f
            ) {
                CornerBadges(item)
            }
            CardMetadata(item)
        }
    }
}

// ── Edge-to-edge photo / screenshot / video ──────────────────────────────────
class PhotoRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = "🖼",
                aspectRatio = 3f / 4f
            ) {
                CornerBadges(item)
            }
            // Minimal chrome: just the source line + resolution chip.
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                PlatformMetadata(item.sourceLabel, item.savedAtMillis, item.style)
                if (item.infoChips.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    InfoChipRow(item.infoChips, item.style)
                }
            }
        }
    }
}

// ── Product ──────────────────────────────────────────────────────────────────
class ProductRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = "🛍",
                aspectRatio = 1f
            ) {
                item.platformBadges().takeIf { it.isNotEmpty() }?.let {
                    BadgeRow(it, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                }
                if (item.isFavorite) FavoriteDot(Modifier.align(Alignment.TopEnd).padding(10.dp))
            }
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
                // Price gets prominence for shopping items.
                val price = item.factBadges().firstOrNull { it.id == "price" }
                if (price != null) {
                    Text(
                        text = price.text,
                        color = item.style.accent,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = item.title,
                    color = item.style.foreground,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                PlatformMetadata(item.sourceLabel, item.savedAtMillis, item.style)
            }
        }
    }
}

// ── Article (hero + preview) ──────────────────────────────────────────────────
class ArticleRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = "📰",
                aspectRatio = 16f / 10f
            ) {
                CornerBadges(item)
            }
            CardMetadata(item)
        }
    }
}

// ── Website / Reddit / Twitter / GitHub / LinkedIn ───────────────────────────
class WebsiteRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = platformFallback(item),
                aspectRatio = 1.91f
            ) {
                CornerBadges(item)
            }
            CardMetadata(item)
        }
    }
}

// ── PDF / Document ────────────────────────────────────────────────────────────
class PdfRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = "📄",
                aspectRatio = 1f / 1.3f
            ) {
                CornerBadges(item)
            }
            CardMetadata(item)
        }
    }
}

// ── Music (horizontal artwork + artist) ───────────────────────────────────────
class MusicRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SavedThumbnail(
                    model = item.thumbnail,
                    style = item.style,
                    fallbackIcon = "🎵",
                    aspectRatio = 1f,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        color = item.style.foreground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.subtitle ?: item.sourceLabel ?: "",
                        color = item.style.foreground.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val duration = item.badges.firstOrNull { it.id == "dur" }
                    if (duration != null) {
                        Spacer(Modifier.height(6.dp))
                        PlatformBadge(duration.text, item.style, icon = "⏱", onDark = false)
                    }
                }
            }
        }
    }
}

// ── Generic fallback ──────────────────────────────────────────────────────────
class GenericRenderer : CardRenderer {
    @Composable
    override fun Render(item: RichSavedItem, modifier: Modifier) {
        BaseContentCard(item = item, modifier = modifier) {
            SavedThumbnail(
                model = item.thumbnail,
                style = item.style,
                fallbackIcon = platformFallback(item),
                aspectRatio = 3f / 2f
            ) {
                CornerBadges(item)
            }
            CardMetadata(item)
        }
    }
}

private fun platformFallback(item: RichSavedItem): String =
    com.amar.vault.ui.renderengine.core.PlatformStyles.iconFor(item.contentType)
