package com.amar.vault.ui.detail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amar.vault.ui.detail.models.DetailTheme
import com.amar.vault.ui.detail.models.HeroPresentation
import com.amar.vault.ui.detail.models.ToolbarPresentation

@Composable
fun HeroRenderer(hero: HeroPresentation, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth()) {
        when (hero) {
            is HeroPresentation.ImageHero -> {
                AsyncImage(
                    model = hero.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is HeroPresentation.VideoHero -> {
                // Placeholder for actual video player component
                AsyncImage(
                    model = hero.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▶", color = Color.White, fontSize = 48.sp)
                }
            }
            is HeroPresentation.AudioHero -> {
                AsyncImage(
                    model = hero.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is HeroPresentation.WebsiteHero, is HeroPresentation.PdfHero -> {
                val thumb = if (hero is HeroPresentation.WebsiteHero) hero.thumbnailUrl else (hero as HeroPresentation.PdfHero).thumbnailUrl
                AsyncImage(
                    model = thumb,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is HeroPresentation.FallbackHero -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFFE0E0E0), Color(0xFFC0C0C0))
                            )
                        )
                )
            }
        }
        
        // Bottom Gradient Overlay for seamless blend into content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xFFF9F9FB)) // Matches theme bg
                    )
                )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FloatingToolbar(
    presentation: ToolbarPresentation,
    theme: DetailTheme,
    alpha: Float,
    onBack: () -> Unit,
    onAction: (String) -> Unit
) {
    TopAppBar(
        title = {
            Column(modifier = Modifier.alpha(alpha)) {
                Text(
                    text = presentation.title.uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.foreground,
                    letterSpacing = 1.sp
                )
                if (presentation.subtitle != null) {
                    Text(
                        text = presentation.subtitle,
                        fontSize = 10.sp,
                        color = theme.foreground.copy(alpha = 0.7f)
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(theme.toolbarBackground.copy(alpha = 0.8f))
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = theme.foreground)
            }
        },
        actions = {
            IconButton(
                onClick = { onAction("share") },
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(CircleShape)
                    .background(theme.toolbarBackground.copy(alpha = 0.8f))
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = theme.foreground)
            }
            IconButton(
                onClick = { onAction("favorite") },
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(CircleShape)
                    .background(theme.toolbarBackground.copy(alpha = 0.8f))
            ) {
                Icon(
                    Icons.Default.Favorite, 
                    contentDescription = "Favorite", 
                    tint = if (presentation.isFavorite) theme.accent else theme.foreground
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = theme.toolbarBackground.copy(alpha = alpha * 0.9f)
        )
    )
}
