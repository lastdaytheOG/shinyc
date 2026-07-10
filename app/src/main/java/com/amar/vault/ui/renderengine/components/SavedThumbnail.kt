package com.amar.vault.ui.renderengine.components

import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.amar.vault.ui.renderengine.models.PlatformStyle

/**
 * A Saved-feature-scoped Coil [ImageLoader]. Kept separate from the global loader
 * (per Phase-2 scope) with its own tuned memory + disk cache so scrolling large
 * saved lists stays smooth without touching app-wide image configuration.
 */
object SavedImageLoader {
    @Volatile private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): ImageLoader =
        ImageLoader.Builder(appContext)
            .crossfade(180)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("saved_thumbnails"))
                    .maxSizeBytes(64L * 1024 * 1024) // 64 MB
                    .build()
            }
            .build()
}

/**
 * The single premium thumbnail surface used by every card. Handles the full
 * lifecycle gracefully:
 *   loading  → shimmer over an accent-tinted gradient
 *   error    → soft gradient + platform fallback glyph
 *   empty    → soft gradient + platform fallback glyph (no jarring gray box)
 *   success  → the image, cropped
 */
@Composable
fun SavedThumbnail(
    model: String?,
    style: PlatformStyle,
    fallbackIcon: String,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    overlay: @Composable (BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val placeholderBrush = style.gradient ?: Brush.linearGradient(
        listOf(style.accent.copy(alpha = 0.20f), style.accent.copy(alpha = 0.05f))
    )

    Box(
        modifier = modifier
            .aspectRatio(aspectRatio)
            .background(placeholderBrush)
    ) {
        if (model.isNullOrBlank()) {
            FallbackGlyph(fallbackIcon, style)
        } else {
            val painter = rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(model)
                    .crossfade(true)
                    .build(),
                imageLoader = SavedImageLoader.get(context)
            )
            // ROOT-CAUSE FIX: AsyncImagePainter only advances its state *while it is
            // being drawn*. The previous code gated the Image behind painter.state, so
            // the moment the state flipped Empty→Loading the Image was pulled out of
            // composition, the painter stopped being drawn, and the request froze in
            // Loading forever — a permanently blank card thumbnail. (The folder collage
            // used Coil's AsyncImage, which always draws its painter, so it never froze.)
            // We now ALWAYS draw the painter and overlay the shimmer/fallback on top,
            // so the request runs to completion exactly like the collage.
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
            when (painter.state) {
                is AsyncImagePainter.State.Loading -> ShimmerBox(style)
                is AsyncImagePainter.State.Error -> FallbackGlyph(fallbackIcon, style)
                else -> Unit
            }
        }
        overlay?.invoke(this)
    }
}

@Composable
private fun BoxScope.FallbackGlyph(icon: String, style: PlatformStyle) {
    Text(
        text = icon,
        fontSize = 34.sp,
        fontWeight = FontWeight.Medium,
        color = style.accent.copy(alpha = 0.85f),
        modifier = Modifier.align(Alignment.Center)
    )
}

@Composable
private fun BoxScope.ShimmerBox(style: PlatformStyle) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )
    val shimmerColors = listOf(
        style.accent.copy(alpha = 0.04f),
        Color.White.copy(alpha = 0.28f),
        style.accent.copy(alpha = 0.04f)
    )
    val x = progress * 700f
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(
                Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(x - 300f, 0f),
                    end = Offset(x, 300f)
                )
            )
    )
}
