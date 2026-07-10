package com.amar.vault.ui.components.save

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.bounceClick
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Spacing
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun FolderPicker(
    categories: List<String>,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit,
    getEmojiForCategory: (String) -> String,
    getColorForCategory: (String) -> Color,
    modifier: Modifier = Modifier,
    onCreateFolder: (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        Text(
            text = "Save to folder",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            modifier = Modifier.padding(horizontal = Spacing.L)
        )
        Spacer(modifier = Modifier.height(Spacing.M))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.L),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (onCreateFolder != null) {
                item(key = "__create__") { NewFolderMini(onClick = onCreateFolder) }
            }
            items(categories, key = { it }) { category ->
                FolderCardMini(
                    name = category,
                    emoji = getEmojiForCategory(category),
                    color = getColorForCategory(category),
                    isSelected = category == selectedCategory,
                    onClick = { onCategorySelected(category) }
                )
            }
        }
    }
}

@Composable
private fun NewFolderMini(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .width(140.dp)
            .bounceClick { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF7F7F7),
        border = BorderStroke(1.dp, CharcoalSoft.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(Spacing.M),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(CharcoalSoft.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Text(text = "＋", fontSize = 18.sp, color = CharcoalSoft) }
            Spacer(modifier = Modifier.width(Spacing.S))
            Text(
                text = "New",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = CharcoalSoft,
                maxLines = 1
            )
        }
    }
}

@Composable
fun FolderCardMini(
    name: String,
    emoji: String,
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = spring(),
        label = "borderWidth"
    )

    Surface(
        modifier = modifier
            .width(140.dp)
            .bounceClick { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) color.copy(alpha = 0.1f) else Color(0xFFF7F7F7),
        border = BorderStroke(borderWidth, color)
    ) {
        Row(
            modifier = Modifier.padding(Spacing.M),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.width(Spacing.S))
            Text(
                text = name.ifBlank { "Vault" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = CharcoalSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Spacer(modifier = Modifier.width(Spacing.XS))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center
                ) { Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
