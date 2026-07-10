package com.amar.vault.ui.components.saved

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.SavedSortOption
import com.amar.vault.SavedTypeFilter
import com.amar.vault.bounceClick
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.WarmBrownDark

/**
 * A sort control (dropdown) plus a horizontally-scrolling set of content-type
 * filter chips. Only filters that have matching items are passed in.
 */
@Composable
fun SavedSortFilterRow(
    currentSort: SavedSortOption,
    onSortSelected: (SavedSortOption) -> Unit,
    filters: List<SavedTypeFilter>,
    selectedFilter: SavedTypeFilter,
    onFilterSelected: (SavedTypeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SortPill(currentSort, onSortSelected)
        Spacer(Modifier.width(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(filters) { filter ->
                FilterChip(
                    filter = filter,
                    selected = filter == selectedFilter,
                    onClick = { onFilterSelected(filter) }
                )
            }
        }
    }
}

@Composable
private fun SortPill(current: SavedSortOption, onSelect: (SavedSortOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(CreamLight)
                .bounceClick { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⇅", fontSize = 13.sp, color = WarmBrownDark, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(5.dp))
            Text(
                current.label,
                fontSize = 12.5.sp,
                color = CharcoalSoft,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SavedSortOption.values().forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option.label,
                            fontWeight = if (option == current) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == current) CharcoalSoft else WarmBrownDark
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun FilterChip(filter: SavedTypeFilter, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        targetValue = if (selected) CharcoalSoft else CreamLight,
        label = "filterBg"
    )
    val fg = if (selected) Color.White else CharcoalSoft
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .bounceClick { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(filter.icon, fontSize = 12.sp)
        Spacer(Modifier.width(5.dp))
        Text(filter.label, fontSize = 12.5.sp, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
