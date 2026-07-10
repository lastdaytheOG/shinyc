package com.amar.vault.ui.components.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.WarmBrown

@Composable
fun QuickFiltersRow(
    filters: List<String>,
    activeFilter: String,
    onFilterSelected: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(filters) { filter ->
            val isSelected = filter == activeFilter
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) CharcoalSoft else CreamDark)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null // Premium soft feel, no ripple
                    ) { onFilterSelected(filter) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = filter,
                    color = if (isSelected) Cream else WarmBrown,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun RecentSearchesView(
    recentSearches: List<String>,
    onSearchSelected: (String) -> Unit,
    onDeleteSearch: (String) -> Unit,
    onClearAll: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent Searches", color = CharcoalSoft, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (recentSearches.isNotEmpty()) {
                TextButton(onClick = onClearAll) {
                    Text("Clear All", color = WarmBrown, fontSize = 14.sp)
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        if (recentSearches.isEmpty()) {
            Text("No recent searches.", color = WarmBrown, fontSize = 14.sp)
        } else {
            recentSearches.forEach { search ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSearchSelected(search) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = WarmBrown, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(search, color = CharcoalSoft, fontSize = 15.sp)
                    }
                    IconButton(onClick = { onDeleteSearch(search) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = WarmBrown)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchSuggestionsView(
    suggestions: List<String>,
    onSuggestionSelected: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Suggestions", color = CharcoalSoft, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        suggestions.forEach { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestionSelected(suggestion) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = WarmBrown, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(suggestion, color = CharcoalSoft, fontSize = 15.sp)
            }
        }
    }
}

@Composable
fun HighlightedText(text: String, query: String, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, fontWeight: FontWeight? = null) {
    val startIndex = if (query.isNotBlank()) text.indexOf(query, ignoreCase = true) else -1
    
    val annotated = buildAnnotatedString {
        if (startIndex >= 0) {
            append(text.substring(0, startIndex))
            withStyle(style = SpanStyle(fontWeight = FontWeight.ExtraBold, background = CreamDark)) {
                append(text.substring(startIndex, startIndex + query.length))
            }
            append(text.substring(startIndex + query.length))
        } else {
            append(text)
        }
    }
    
    Text(
        text = annotated,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight
    )
}

@Composable
fun EmptyStateView(query: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No results for \"$query\"",
                color = CharcoalSoft,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Try adjusting your filters or spelling.",
                color = WarmBrown,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun LoadingSkeletonView() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        repeat(4) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CreamDark)
            )
        }
    }
}
