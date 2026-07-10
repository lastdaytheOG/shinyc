package com.amar.vault.ui.components.folder.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import coil.compose.AsyncImage
import com.amar.vault.ContentSpecies
import com.amar.vault.StashItemWithVaultItem
import com.amar.vault.ui.components.folder.BaseContentCard
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Spacing
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun YoutubeCard(
    item: StashItemWithVaultItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    BaseContentCard(
        onClick = onClick,
        onLongClick = onLongClick,
        aspectRatio = ContentSpecies.YOUTUBE_VIDEO.aspectRatio,
        modifier = modifier,
        imageContent = {
            if (item.thumbnailPath != null) {
                AsyncImage(
                    model = item.thumbnailPath,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ContentSpecies.YOUTUBE_VIDEO.washColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🎥", fontSize = 48.sp)
                }
            }

            // Play Button Overlay
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        },
        footerContent = {
            Text(
                text = item.title ?: "YouTube Video",
                color = CharcoalSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(Spacing.XS))
            Text(
                text = item.sourceApp.ifBlank { "YouTube" },
                color = WarmBrownDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}
