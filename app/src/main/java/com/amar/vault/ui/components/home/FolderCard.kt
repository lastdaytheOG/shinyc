package com.amar.vault.ui.components.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amar.vault.ui.renderengine.components.SavedImageLoader
import com.amar.vault.ui.renderengine.components.relativeTime
import com.amar.vault.ui.theme.FolderStyle
import com.amar.vault.ui.theme.Spacing
import com.amar.vault.bounceClick

/**
 * Album-style category tile. Instead of one generic cover, it builds a **smart
 * collage** from the folder's real item thumbnails (#5) so a Recipes folder reads
 * as a food collage, Photography as recent photos, etc. When the folder is empty
 * it falls back to its themed fill (#4) with the folder icon — never a flat gray
 * rectangle (#8). Every image cell shows an accent wash while loading, so there is
 * no gray flash on scroll.
 */
@Composable
fun FolderCard(
    style: FolderStyle,
    title: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    previewThumbnails: List<String> = emptyList(),
    lastUpdated: Long = 0L
) {
    val hasImages = previewThumbnails.isNotEmpty()

    Box(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(24.dp), ambientColor = style.accent, spotColor = style.accent, clip = false)
            .clip(RoundedCornerShape(24.dp))
            .background(brush = style.background)
            .bounceClick { onClick() }
            .height(150.dp)
    ) {
        if (hasImages) {
            PreviewCollage(
                thumbnails = previewThumbnails,
                accent = style.accent,
                modifier = Modifier.fillMaxSize()
            )
            // Legibility scrim so the label stays readable over the collage.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.02f), Color.Black.copy(alpha = 0.58f))
                        )
                    )
            )
        }

        val fg = if (hasImages) Color.White else style.foreground

        Column(
            modifier = Modifier.fillMaxSize().padding(Spacing.L),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (hasImages) {
                    // Small floating icon chip keeps the glyph legible over photos.
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.28f)),
                        contentAlignment = Alignment.Center
                    ) { Text(text = style.icon, fontSize = 18.sp) }
                } else {
                    Text(text = style.icon, fontSize = 30.sp)
                }
                if (isPinned) Text(text = "★", fontSize = 16.sp, color = fg.copy(alpha = 0.95f))
            }
            Spacer(Modifier.weight(1f))
            Column {
                Text(
                    text = title,
                    color = fg,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Spacing.XS))
                val meta = buildString {
                    append(if (count == 1) "1 save" else "$count saves")
                    if (lastUpdated > 0L) append("  ·  ${relativeTime(lastUpdated)}")
                }
                Text(text = meta, color = fg.copy(alpha = 0.85f), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * Content-aware collage: 1 image fills; 2 split vertically; 3 = one hero + two
 * stacked; 4+ = a 2×2 mosaic. Cells never leave a gray gap — the accent wash sits
 * behind each image.
 */
@Composable
private fun PreviewCollage(thumbnails: List<String>, accent: Color, modifier: Modifier = Modifier) {
    val imgs = thumbnails.take(4)
    val gap = 2.dp
    Box(modifier = modifier) {
        when (imgs.size) {
            1 -> CollageCell(imgs[0], accent, Modifier.fillMaxSize())
            2 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CollageCell(imgs[0], accent, Modifier.weight(1f).fillMaxHeight())
                CollageCell(imgs[1], accent, Modifier.weight(1f).fillMaxHeight())
            }
            3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                CollageCell(imgs[0], accent, Modifier.weight(1.4f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    CollageCell(imgs[1], accent, Modifier.weight(1f).fillMaxWidth())
                    CollageCell(imgs[2], accent, Modifier.weight(1f).fillMaxWidth())
                }
            }
            else -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CollageCell(imgs[0], accent, Modifier.weight(1f).fillMaxHeight())
                    CollageCell(imgs[1], accent, Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    CollageCell(imgs[2], accent, Modifier.weight(1f).fillMaxHeight())
                    CollageCell(imgs[3], accent, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun CollageCell(model: String, accent: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.10f)))
        )
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).crossfade(true).build(),
            imageLoader = SavedImageLoader.get(context),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}
