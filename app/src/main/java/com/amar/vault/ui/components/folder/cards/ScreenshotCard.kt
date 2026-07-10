package com.amar.vault.ui.components.folder.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.amar.vault.ui.theme.Spacing

@Composable
fun ScreenshotCard(
    item: StashItemWithVaultItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    BaseContentCard(
        onClick = onClick,
        onLongClick = onLongClick,
        aspectRatio = ContentSpecies.SCREENSHOT.aspectRatio, // Often null (wrap content)
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
                        .background(ContentSpecies.SCREENSHOT.washColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📱", fontSize = 48.sp)
                }
            }

            // Gradient Overlay at bottom for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                            startY = 100f
                        )
                    )
            )

            // OCR badge overlay
            if (item.ocrText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.M)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                            .padding(horizontal = Spacing.S, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("T", fontSize = 10.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.width(Spacing.XS))
                        Text(
                            text = item.ocrText.take(20).replace("\n", " "),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        footerContent = null // Screenshots usually don't need a footer if they speak for themselves
    )
}
