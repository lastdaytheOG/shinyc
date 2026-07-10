package com.amar.vault.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amar.vault.bounceClick
import com.amar.vault.ui.detail.components.*
import com.amar.vault.ui.detail.models.DetailPresentationModel
import com.amar.vault.ui.renderengine.models.RichSavedItem

@Composable
fun DetailScreen(
    item: RichSavedItem,
    presentation: DetailPresentationModel,
    onBack: () -> Unit,
    onAction: (String) -> Unit,
    onRelatedClick: (RichSavedItem) -> Unit = {}
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(presentation.theme.background)
    ) {
        // Adaptive: keep the reading column centered and comfortable on foldables,
        // tablets and landscape; the hero stays full-bleed.
        val wide = maxWidth >= 640.dp
        val contentPad = if (wide) ((maxWidth - 600.dp) / 2).coerceAtLeast(24.dp) else 24.dp
        val heroHeight = (maxHeight * 0.5f).coerceIn(260.dp, if (wide) 520.dp else 420.dp)

        val listState = rememberLazyListState()
        val scrollOffset = listState.firstVisibleItemScrollOffset
        val firstItemIndex = listState.firstVisibleItemIndex
        val density = LocalDensity.current
        val heroHeightPx = with(density) { heroHeight.toPx() }
        val parallaxOffset = if (firstItemIndex == 0) scrollOffset * 0.5f else heroHeightPx * 0.5f
        val toolbarAlpha = if (firstItemIndex > 0) 1f else (scrollOffset / (heroHeightPx * 0.7f)).coerceIn(0f, 1f)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                HeroRenderer(
                    hero = presentation.hero,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroHeight)
                        .graphicsLayer { translationY = parallaxOffset }
                )
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-40).dp)
                        .padding(horizontal = contentPad)
                ) {
                    TitleBlock(presentation.titleBlock, presentation.theme)
                    Spacer(Modifier.height(16.dp))
                    MetadataChipGroup(presentation.metadataChips)
                    Spacer(Modifier.height(20.dp))
                    ActionGrid(presentation.primaryActions, presentation.theme, onAction)
                    Spacer(Modifier.height(12.dp))
                    QuickActionRow(presentation.quickActions, presentation.theme, onAction)
                    Spacer(Modifier.height(28.dp))
                }
            }

            items(presentation.informationSections, key = { it.id }) { section ->
                SectionCard(
                    section = section,
                    theme = presentation.theme,
                    modifier = Modifier.padding(horizontal = contentPad).padding(bottom = 12.dp)
                )
            }

            presentation.relatedItems?.let { related ->
                item {
                    Text(
                        text = "FROM THE SAME SHARE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = presentation.theme.foreground.copy(alpha = 0.45f),
                        modifier = Modifier.padding(horizontal = contentPad).padding(top = 8.dp, bottom = 12.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = contentPad),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        related.items.forEach { rel ->
                            RelatedCard(rel, onClick = { onRelatedClick(rel) })
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(28.dp))
                DangerZone(
                    presentation = presentation.dangerZone,
                    onAction = onAction,
                    modifier = Modifier.padding(horizontal = contentPad).padding(bottom = 64.dp)
                )
            }
        }

        FloatingToolbar(
            presentation = presentation.toolbar,
            theme = presentation.theme,
            alpha = toolbarAlpha,
            onBack = onBack,
            onAction = onAction
        )
    }
}

@Composable
private fun RelatedCard(item: RichSavedItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(item.style.background)
            .bounceClick { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(item.style.accent.copy(alpha = 0.12f))
        ) {
            if (item.thumbnail != null) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    com.amar.vault.ui.renderengine.core.PlatformStyles.iconFor(item.contentType),
                    fontSize = 26.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        Text(
            text = item.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = item.style.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp)
        )
    }
}
