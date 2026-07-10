package com.amar.vault.ui.components.folder.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun ProductCard(
    item: StashItemWithVaultItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    BaseContentCard(
        onClick = onClick,
        onLongClick = onLongClick,
        aspectRatio = ContentSpecies.PRODUCT.aspectRatio,
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
                        .background(ContentSpecies.PRODUCT.washColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🛍️", fontSize = 48.sp)
                }
            }
            
            // Try to extract price from OCR text as a fallback, or just hide if missing
            val priceRegex = Regex("""[\$£€₹¥]\s*\d+(?:[.,]\d+)?|\d+(?:[.,]\d+)?\s*(?:USD|EUR|GBP|INR)""")
            val priceMatch = priceRegex.find(item.ocrText)
            
            if (priceMatch != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(Spacing.S)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.9f))
                        .padding(horizontal = Spacing.S, vertical = Spacing.XS)
                ) {
                    Text(
                        text = priceMatch.value,
                        fontWeight = FontWeight.Bold,
                        color = CharcoalSoft,
                        fontSize = 12.sp
                    )
                }
            }
        },
        footerContent = {
            Text(
                text = item.title ?: "Product",
                color = CharcoalSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(Spacing.XS))
            Text(
                text = item.sourceApp.ifBlank { "Store" },
                color = WarmBrownDark,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}
