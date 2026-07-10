package com.amar.vault.ui.renderengine.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Elegant loading state shown while the Saved feed resolves. Mirrors the masonry
 * layout of real cards so the transition to loaded content feels seamless rather
 * than a spinner-to-content jump.
 */
@Composable
fun SavedSkeletonGrid(
    columns: Int,
    modifier: Modifier = Modifier
) {
    // Varied heights to evoke the staggered card feed.
    val ratios = listOf(1.3f, 0.9f, 1.6f, 1.1f, 1.4f, 1.0f, 1.7f, 1.2f)
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 12.dp,
        modifier = modifier.fillMaxSize(),
        userScrollEnabled = false
    ) {
        for (i in 0 until 8) {
            item(key = "skeleton_$i") {
                SkeletonCard(aspectRatio = ratios[i % ratios.size])
            }
        }
    }
}

@Composable
private fun SkeletonCard(aspectRatio: Float) {
    val shimmer = shimmerBrush()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFFFAF7F3))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .background(shimmer)
        )
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .height(13.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(11.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(shimmer)
            )
        }
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "skeletonProgress"
    )
    val x = progress * 900f
    return Brush.linearGradient(
        colors = listOf(
            Color(0xFFEDE6DC).copy(alpha = 0.6f),
            Color(0xFFF5F0EA),
            Color(0xFFEDE6DC).copy(alpha = 0.6f)
        ),
        start = Offset(x - 400f, 0f),
        end = Offset(x, 400f)
    )
}
