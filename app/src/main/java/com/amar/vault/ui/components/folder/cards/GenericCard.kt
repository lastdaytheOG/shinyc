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
fun GenericCard(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    BaseContentCard(
        onClick = onClick,
        onLongClick = onLongClick,
        aspectRatio = species.aspectRatio ?: 1f,
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
                        .background(species.washColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(species.emoji, fontSize = 48.sp)
                }
            }
        },
        footerContent = {
            Text(
                text = item.title ?: "Saved Item",
                color = CharcoalSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(Spacing.XS))
            Text(
                text = item.sourceApp.ifBlank { "Vault" },
                color = WarmBrownDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}
