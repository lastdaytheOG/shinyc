package com.amar.vault.ui.components.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.CardBorder
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Spacing

@Composable
fun VaultFilterBar(
    filters: List<String>,
    selectedFilter: String,
    onFilterSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.L),
        horizontalArrangement = Arrangement.spacedBy(Spacing.S)
    ) {
        items(filters) { filter ->
            val isSelected = filter == selectedFilter
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) CharcoalSoft else Color.White,
                animationSpec = spring(),
                label = "FilterBgColor"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) Color.White else CharcoalSoft,
                animationSpec = spring(),
                label = "FilterTextColor"
            )
            val borderColor = if (isSelected) Color.Transparent else CardBorder

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = bgColor,
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier
                    .height(36.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clickable { onFilterSelected(filter) }
                        .padding(horizontal = Spacing.M)
                ) {
                    Text(
                        text = filter,
                        color = textColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
