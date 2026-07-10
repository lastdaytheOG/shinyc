package com.amar.vault.ui.renderengine.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.amar.vault.ui.renderengine.models.PlatformStyle

/**
 * Reusable platform-identity primitives referenced by every card renderer so the
 * origin of a saved item is communicated consistently, never hardcoded per card.
 *
 *  - [PlatformAccent]   : a small brand-colored dot
 *  - [PlatformBadge]    : a floating pill (icon + label) for overlays on imagery
 *  - [PlatformMetadata] : the "source · saved 2h ago" line beneath the title
 */

@Composable
fun PlatformAccent(
    style: PlatformStyle,
    modifier: Modifier = Modifier,
    size: Int = 8
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(style.accent)
    )
}

@Composable
fun PlatformBadge(
    label: String,
    style: PlatformStyle,
    modifier: Modifier = Modifier,
    icon: String? = null,
    onDark: Boolean = false
) {
    // On imagery we render a translucent dark chip with white text for legibility;
    // in-flow we render a soft accent-tinted chip.
    val bg = if (onDark) Color.Black.copy(alpha = 0.55f) else style.accent.copy(alpha = 0.14f)
    val fg = if (onDark) Color.White else style.accent
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) Text(icon, fontSize = 11.sp)
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PlatformMetadata(
    sourceLabel: String?,
    savedAtMillis: Long,
    style: PlatformStyle,
    modifier: Modifier = Modifier
) {
    val parts = buildList {
        if (!sourceLabel.isNullOrBlank()) add(sourceLabel)
        if (savedAtMillis > 0L) add(relativeTime(savedAtMillis))
    }
    if (parts.isEmpty()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PlatformAccent(style = style, size = 6)
        Text(
            text = parts.joinToString("  ·  "),
            color = style.foreground.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Compact, human "saved N ago" formatting. */
fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    if (diff < 0) return "just now"
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        days < 30 -> "${days / 7}w ago"
        days < 365 -> "${days / 30}mo ago"
        else -> "${days / 365}y ago"
    }
}
