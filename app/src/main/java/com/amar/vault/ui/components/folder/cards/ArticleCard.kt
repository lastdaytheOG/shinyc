package com.amar.vault.ui.components.folder.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amar.vault.ContentSpecies
import com.amar.vault.StashItemWithVaultItem
import com.amar.vault.ui.components.folder.BaseContentCard
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Spacing
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun ArticleCard(
    item: StashItemWithVaultItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    BaseContentCard(
        onClick = onClick,
        onLongClick = onLongClick,
        aspectRatio = ContentSpecies.DOCUMENT.aspectRatio ?: 1f,
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
                        .background(Color(0xFFF5F5F5)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📰", fontSize = 48.sp)
                }
            }
        },
        footerContent = {
            Text(
                text = item.sourceApp.ifBlank { "Article" }.uppercase(),
                color = WarmBrownDark,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(Spacing.XS))
            Text(
                text = item.title ?: "Document",
                color = CharcoalSoft,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
        }
    )
}
