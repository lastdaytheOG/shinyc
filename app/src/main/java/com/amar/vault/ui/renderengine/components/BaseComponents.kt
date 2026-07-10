package com.amar.vault.ui.renderengine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.renderengine.models.BadgeInfo
import com.amar.vault.ui.renderengine.models.PlatformStyle
import com.amar.vault.ui.renderengine.models.RichSavedItem

private val CardShape = RoundedCornerShape(22.dp)

/**
 * The premium container for every card: soft accent-tinted elevation, generous
 * rounding, no harsh borders. Gesture handling (tap → detail, long-press → sheet)
 * and spring-press feedback are owned by the caller via the passed-in [modifier],
 * so this stays a pure visual shell.
 */
@Composable
fun BaseContentCard(
    item: RichSavedItem,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 10.dp,
                shape = CardShape,
                ambientColor = item.style.accent.copy(alpha = 0.5f),
                spotColor = item.style.accent.copy(alpha = 0.5f),
                clip = false
            )
            .clip(CardShape)
            .background(item.style.background)
    ) {
        content()
    }
}

/**
 * Standard title + subtitle + platform-source line + derived info chips.
 * Used by nearly every renderer so typography and spacing stay consistent.
 */
@Composable
fun CardMetadata(
    item: RichSavedItem,
    modifier: Modifier = Modifier,
    showSubtitle: Boolean = true
) {
    Column(modifier = modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp)) {
        Text(
            text = item.title,
            color = item.style.foreground,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (showSubtitle && !item.subtitle.isNullOrBlank() && item.subtitle != item.sourceLabel) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.subtitle,
                color = item.style.foreground.copy(alpha = 0.7f),
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        PlatformMetadata(
            sourceLabel = item.sourceLabel,
            savedAtMillis = item.savedAtMillis,
            style = item.style
        )
        if (item.infoChips.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            InfoChipRow(item.infoChips, item.style)
        }
    }
}

@Composable
fun InfoChipRow(
    chips: List<String>,
    style: PlatformStyle,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.take(3).forEach { chip ->
            Text(
                text = chip,
                color = style.foreground.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(style.foreground.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * Row of platform/duration/price badges, rendered as translucent dark pills so they
 * stay legible when floated over imagery.
 */
@Composable
fun BadgeRow(
    badges: List<BadgeInfo>,
    modifier: Modifier = Modifier,
    onDark: Boolean = true
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        badges.forEach { badge ->
            PlatformBadge(
                label = badge.text,
                style = badge.style,
                icon = badge.icon,
                onDark = onDark
            )
        }
    }
}

/** Small circular favorite heart overlay for imagery. */
@Composable
fun FavoriteDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text("❤", fontSize = 11.sp)
    }
}
