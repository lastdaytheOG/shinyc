package com.amar.vault.ui.components.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.CategorySummary
import com.amar.vault.FolderMeta
import com.amar.vault.bounceClick
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.FolderVisuals
import com.amar.vault.ui.theme.Spacing
import com.amar.vault.ui.theme.StyleAllItems
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun FolderGrid(
    itemCount: Int,
    summaries: List<CategorySummary>,
    onAllItemsClick: () -> Unit,
    onCategoryClick: (String, String) -> Unit,
    onCreateFolderClick: () -> Unit,
    modifier: Modifier = Modifier,
    pinnedCategories: Set<String> = emptySet(),
    folderMeta: Map<String, FolderMeta> = emptyMap(),
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    columns: Int = 2
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(Spacing.M),
        verticalItemSpacing = Spacing.M,
        contentPadding = PaddingValues(horizontal = Spacing.L, vertical = Spacing.M),
        modifier = modifier.fillMaxSize()
    ) {
        // "All Items" special tile
        item(key = "__all__") {
            FolderCard(
                style = StyleAllItems,
                title = "All Items",
                count = itemCount,
                onClick = onAllItemsClick
            )
        }

        items(summaries, key = { it.category.ifBlank { "__uncat__" } }) { summary ->
            val catName = summary.category.ifBlank { "Uncategorized" }
            val style = FolderVisuals.styleFor(catName, folderMeta[summary.category.ifBlank { catName }])
            FolderCard(
                style = style,
                title = catName,
                count = summary.count,
                onClick = { onCategoryClick(summary.category, catName) },
                isPinned = summary.category in pinnedCategories,
                previewThumbnails = summary.previewThumbnails,
                lastUpdated = summary.lastUpdated
            )
        }

        // "+ New Folder" tile (#3)
        item(key = "__new_folder__") {
            NewFolderTile(onClick = onCreateFolderClick)
        }
    }
}

@Composable
private fun NewFolderTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent)
            .border(1.5.dp, CreamDark, RoundedCornerShape(24.dp))
            .bounceClick { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("＋", fontSize = 34.sp, fontWeight = FontWeight.Light, color = WarmBrownDark)
            Text(
                "New Folder",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = CharcoalSoft,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
