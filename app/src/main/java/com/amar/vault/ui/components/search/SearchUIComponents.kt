package com.amar.vault.ui.components.search

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.amar.vault.ContentSpecies
import com.amar.vault.ItemType
import com.amar.vault.PdfPreviewGenerator
import com.amar.vault.SearchHit
import com.amar.vault.bounceClick
import com.amar.vault.queryWordsOf
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.PdfColor
import com.amar.vault.ui.theme.PdfWash
import com.amar.vault.ui.theme.ScreenshotColor
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

/**
 * A document in the result list: its name, the words that matched with the query picked out,
 * and the page they are on — which is the page a tap opens.
 */
@Composable
fun DocumentHitCard(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = hit.row
    val context = LocalContext.current
    // First page as the thumbnail. Only a PDF has one to draw; a Word or Excel file keeps the icon.
    val isPdf = remember(item) { ContentSpecies.classify(item) == ContentSpecies.PDF }
    var preview by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.uri, isPdf) {
        if (isPdf) preview = withContext(Dispatchers.IO) { PdfPreviewGenerator.generateFirstPagePreview(context, item.uri) }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick(onLongClick = onLongClick, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(CreamLight)
            .border(1.dp, CreamDark, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 58.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(0.5.dp, CreamDark, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            val page = preview
            if (page != null) {
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("📄", fontSize = 18.sp)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title ?: item.sourceFile.substringAfterLast('/').ifBlank { "Document" },
                color = CharcoalSoft,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            hit.excerpt?.let { excerpt ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = remember(excerpt, query, hit.similarWord) {
                        withQueryWordsMarked(excerpt, hit.similarWord ?: query)
                    },
                    color = WarmBrownDark,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            hit.filedUnder?.let { tag ->
                Spacer(Modifier.height(4.dp))
                FiledUnderLine(tag, "not a word on the page.")
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(PdfColor))
                    Spacer(Modifier.width(6.dp))
                    Text("Document", color = WarmBrownDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                hit.page?.let { page ->
                    Text(
                        text = "Opens at page $page",
                        color = PdfColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(PdfWash)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * A photo or screenshot in the result list: the picture itself, and the words read off it that
 * matched. A tap opens the picture.
 */
@Composable
fun ImageHitCard(
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = hit.row
    // What was read off the picture, for when no part of it is being pointed at.
    val readOffIt = remember(item.ocrText) {
        item.ocrText.replace(Regex("\\s+"), " ").trim().take(140)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .bounceClick(onLongClick = onLongClick, onClick = onClick)
            .clip(RoundedCornerShape(12.dp))
            .background(CreamLight)
            .border(1.dp, CreamDark, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Image(
            painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.thumbnailPath?.takeIf { it.isNotBlank() } ?: item.uri)
                    .crossfade(true)
                    .build()
            ),
            contentDescription = null,
            modifier = Modifier
                .size(width = 64.dp, height = 84.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(CreamDark)
                .border(0.5.dp, CreamDark, RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            item.title?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = title,
                    color = CharcoalSoft,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
            }
            val excerpt = hit.excerpt
            if (excerpt != null) {
                Text(
                    text = remember(excerpt, query, hit.similarWord) {
                        withQueryWordsMarked(excerpt, hit.similarWord ?: query)
                    },
                    color = WarmBrownDark,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (hit.filedUnder != null) {
                FiledUnderLine(hit.filedUnder, "not a word in the picture.")
            } else if (readOffIt.isNotEmpty()) {
                Text(
                    text = readOffIt,
                    color = WarmBrownDark,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(ScreenshotColor))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (item.itemType == ItemType.SCREENSHOT) "Screenshot" else "Image",
                    color = WarmBrownDark,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Why an item is in the list when it does not say the typed word: the app filed it under a tag
 * that does ([SearchHit.filedUnder]).
 */
@Composable
private fun FiledUnderLine(tag: String, whatItLacks: String) {
    Text(
        text = buildAnnotatedString {
            append("Tagged ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = CharcoalSoft)) { append(tag) }
            append(" by the app — ")
            append(whatItLacks)
        },
        color = WarmBrown,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/** [text] with every occurrence of each word of [query] picked out. */
internal fun withQueryWordsMarked(text: String, query: String) = buildAnnotatedString {
    append(text)
    val marked = SpanStyle(fontWeight = FontWeight.Bold, color = CharcoalSoft, background = CreamDark)
    for (word in queryWordsOf(query)) {
        var at = text.indexOf(word, ignoreCase = true)
        while (at >= 0) {
            addStyle(marked, at, at + word.length)
            at = text.indexOf(word, at + word.length, ignoreCase = true)
        }
    }
}

/**
 * [filter] is the chip that is on when it is anything but "All": the list is then empty under
 * that chip, which is not the same as the vault having nothing, and [onSearchEverything] turns
 * the chip off.
 */
@Composable
fun EmptyStateView(query: String, filter: String? = null, onSearchEverything: (() -> Unit)? = null) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (filter == null) "No results for \"${query.trim()}\""
                else "Nothing under $filter for \"${query.trim()}\"",
                color = CharcoalSoft,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (filter == null) "Try adjusting your filters or spelling."
                else "It may be under another filter.",
                color = WarmBrown,
                fontSize = 14.sp
            )
            if (onSearchEverything != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Search everything",
                    color = Cream,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(CharcoalSoft)
                        .clickable(onClick = onSearchEverything)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
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
