package com.amar.vault.ui.components.save

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.amar.vault.ui.theme.Spacing

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val shimmerColors = listOf(
        Color(0xFFE5E5E5),
        Color(0xFFF2F2F2),
        Color(0xFFE5E5E5),
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.L)
    ) {
        // Hero Image Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(Spacing.L))
        
        // Metadata Skeletons
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        Spacer(modifier = Modifier.height(Spacing.S))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(16.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(Spacing.XL))
        
        // Folders skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
        )
        
        Spacer(modifier = Modifier.height(Spacing.XL))
        
        // Buttons Skeleton
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(brush)
        )
    }
}
