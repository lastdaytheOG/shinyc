package com.amar.vault

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.amar.vault.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CollectibleVaultCard(
    item: StashItemWithVaultItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    screenBg: Color,
    cardBg: Color,
    primaryText: Color,
    secondaryText: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    val species = remember(item) { ContentSpecies.classify(item) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .bounceClick(onLongClick = onLongClick, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            when (species) {
                ContentSpecies.REEL -> ReelCardContent(item, species, primaryText, secondaryText, cardBg, screenBg)
                ContentSpecies.YOUTUBE_VIDEO -> YouTubeCardContent(item, species, primaryText, secondaryText)
                ContentSpecies.AUDIO -> AudioCardContent(item, species, primaryText, secondaryText, cardBg)
                ContentSpecies.PRODUCT -> ProductCardContent(item, species, primaryText, secondaryText)
                ContentSpecies.SCREENSHOT -> ScreenshotCardContent(item, species, primaryText, secondaryText)
                ContentSpecies.PDF -> PdfCardContent(item, species, primaryText, secondaryText, cardBg, borderColor)
                ContentSpecies.APP_LISTING -> AppListingCardContent(item, species, primaryText, secondaryText, cardBg, borderColor)
                ContentSpecies.LOCATION -> LocationCardContent(item, species, primaryText, secondaryText, cardBg)
                ContentSpecies.WEBSITE -> WebsiteCardContent(item, species, primaryText, secondaryText)
                ContentSpecies.DOCUMENT -> GenericDocumentCardContent(item, species, primaryText, secondaryText)
            }
        }
    }
}

// ── Chrome for type indicator (colored dot + label) ─────────────────────────
@Composable
private fun CardChrome(
    species: ContentSpecies,
    secondaryText: Color,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(species.dotColor)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = species.label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = secondaryText,
                letterSpacing = 0.5.sp
            )
        }
        if (trailingContent != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingContent()
            }
        }
    }
}

// ── 1. REEL CARD CONTENT (9:16 aspect, caption overlaid on dark strip) ─────
@Composable
private fun ReelCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color,
    cardBg: Color,
    screenBg: Color
) {
    val context = LocalContext.current
    val caption = remember(item.uri, item.title, item.ocrText) {
        val extracted = extractInstagramCaption(item.uri) ?: item.title ?: item.ocrText.take(60)
        cleanDisplayTitle(extracted, item.uri, "Instagram")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(9f / 16f)
            .background(Color(0xFF1E1C1A))
    ) {
        // Media Preview
        val hasPreview = remember(item.thumbnailPath, item.sourceFile, item.uri) {
            !item.thumbnailPath.isNullOrBlank() || item.sourceFile.isNotBlank() || (!item.uri.startsWith("http") && item.uri.isNotBlank())
        }
        if (hasPreview) {
            val model = when {
                !item.thumbnailPath.isNullOrBlank() -> item.thumbnailPath
                item.sourceFile.isNotBlank() -> item.sourceFile
                else -> item.uri
            }
            val painter = rememberAsyncImagePainter(model = coil.request.ImageRequest.Builder(LocalContext.current).data(model).crossfade(400).build())
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Gradient overlay for legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            startY = 300f
                        )
                    )
            )
        } else {
            // Visual premium gradient matching Instagram reels
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFE1306C), Color(0xFFC13584), Color(0xFF833AB4))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🎬", fontSize = 42.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Instagram Reel",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Chrome at top (Favorite overlay + dot chrome overlay)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Semi-transparent tag for chrome
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(species.dotColor)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = species.label,
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (item.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = ReelColor,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Caption and Metadata overlaid on a solid dark strip at the base of the media
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(10.dp)
        ) {
            Text(
                text = caption,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "0:15  •  ${SourceResolver.getReadableAppName(item.sourceApp, item.uri)}",
                    fontSize = 9.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── 2. YOUTUBE CARD CONTENT (16:9, duration badge sits in thumbnail) ──────
@Composable
private fun YouTubeCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color
) {
    val videoId = remember(item.uri) { extractYouTubeVideoId(item.uri) }
    val thumbnailUrl = "https://img.youtube.com/vi/$videoId/mqdefault.jpg"
    val duration = remember(item.ocrText) { extractDuration(item.ocrText) }
    val cleanTitle = remember(item.title, item.uri) { cleanDisplayTitle(item.title, item.uri, "YouTube") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (videoId != null) {
                val painter = rememberAsyncImagePainter(model = thumbnailUrl)
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )

                // Play symbol
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Duration badge sits inside the thumbnail corner, not in text block
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = duration,
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFFF0000), Color(0xFF7F0000))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎥", fontSize = 36.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "YouTube Video",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Title and Metadata block below media (12dp gap)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = cleanTitle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp)) // 8dp gap to metadata
            CardChrome(species = species, secondaryText = secondaryText) {
                Text(
                    text = "YouTube  •  148K views",
                    fontSize = 8.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 3. AUDIO CARD CONTENT (Square art, horizontal layout, waveform, play) ─
@Composable
private fun AudioCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color,
    cardBg: Color
) {
    val details = remember(item.title) { extractSpotifyDetails(item.title) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Square art (56dp)
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF282828)),
            contentAlignment = Alignment.Center
        ) {
            val hasArt = remember(item.thumbnailPath, item.sourceFile, item.uri) {
                !item.thumbnailPath.isNullOrBlank() || item.sourceFile.isNotBlank() || (!item.uri.startsWith("http") && item.uri.isNotBlank())
            }
            if (hasArt) {
                val model = when {
                    !item.thumbnailPath.isNullOrBlank() -> item.thumbnailPath
                    item.sourceFile.isNotBlank() -> item.sourceFile
                    else -> item.uri
                }
                val painter = rememberAsyncImagePainter(model = coil.request.ImageRequest.Builder(LocalContext.current).data(model).crossfade(400).build())
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("🎵", fontSize = 24.sp)
            }
        }

        Spacer(Modifier.width(12.dp)) // 12dp media-to-text gap

        // Metadata & Waveform
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = details.first,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = details.second,
                fontSize = 9.sp,
                color = secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(Modifier.height(6.dp))
            
            // Signature detail: Static Waveform bars
            Row(
                modifier = Modifier.height(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val barHeights = listOf(4.dp, 8.dp, 6.dp, 10.dp, 5.dp, 7.dp, 3.dp, 9.dp, 6.dp, 8.dp)
                barHeights.forEach { height ->
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(height)
                            .clip(RoundedCornerShape(1.dp))
                            .background(SpotifyColor)
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "3:42",
                    fontSize = 8.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Trailing Play Control
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(secondaryText.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = primaryText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── 4. PRODUCT CARD CONTENT (1:1 shelf tone, price ribbon tag) ─────────────
@Composable
private fun ProductCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color
) {
    val cleanTitle = remember(item.title, item.uri) { cleanDisplayTitle(item.title, item.uri, "Product") }
    val price = remember(item.ocrText) { extractPrice(item.ocrText) ?: "₹14,999" }
    val sourceName = remember(item.sourceApp, item.uri) { SourceResolver.getReadableAppName(item.sourceApp, item.uri) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFFFAF6F0)) // shelf-tone light background
                .padding(8.dp)
        ) {
            val hasPreview = remember(item.thumbnailPath, item.sourceFile, item.uri) {
                !item.thumbnailPath.isNullOrBlank() || item.sourceFile.isNotBlank() || (!item.uri.startsWith("http") && item.uri.isNotBlank())
            }
            if (hasPreview) {
                val model = when {
                    !item.thumbnailPath.isNullOrBlank() -> item.thumbnailPath
                    item.sourceFile.isNotBlank() -> item.sourceFile
                    else -> item.uri
                }
                val painter = rememberAsyncImagePainter(model = coil.request.ImageRequest.Builder(LocalContext.current).data(model).crossfade(400).build())
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text("🛍️", fontSize = 38.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = sourceName.uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = ProductColor.copy(alpha = 0.8f)
                    )
                }
            }

            // Price Ribbon Tag (Signature Detail)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .background(
                        color = ProductColor,
                        shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 6.dp, bottomEnd = 6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = price,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Description & Metadata
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = cleanTitle,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            CardChrome(species = species, secondaryText = secondaryText) {
                Text(
                    text = "$sourceName  •  ⭐ 4.5",
                    fontSize = 8.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 5. SCREENSHOT CARD CONTENT (Native aspect ratio, auto category) ────────
@Composable
private fun ScreenshotCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color
) {
    val autoCategory = remember(item.ocrText) {
        val ocr = item.ocrText.lowercase()
        when {
            ocr.contains("total") || ocr.contains("receipt") || ocr.contains("invoice") || ocr.contains("tax") -> "Receipt"
            ocr.contains("chat") || ocr.contains("message") || ocr.contains("reply") -> "Chat conversation"
            ocr.contains("article") || ocr.contains("author") || ocr.contains("published") -> "Article"
            else -> "App Screen capture"
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .background(Color(0xFFEDE6DC))
        ) {
            // Render at full scale up to native limit without cropping frame
            val model = remember(item.thumbnailPath, item.uri, item.sourceFile) {
                when {
                    !item.thumbnailPath.isNullOrBlank() -> item.thumbnailPath
                    item.uri.startsWith("http") || item.uri.isBlank() -> item.sourceFile
                    else -> item.uri
                }
            }
            val painter = rememberAsyncImagePainter(model = coil.request.ImageRequest.Builder(LocalContext.current).data(model).crossfade(400).build())
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp),
                contentScale = ContentScale.FillWidth
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = item.title ?: "Screenshot",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            CardChrome(species = species, secondaryText = secondaryText) {
                Text(
                    text = autoCategory,
                    fontSize = 8.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 6. PDF CARD CONTENT (Stacked paper shape, page count, file size) ────────
@Composable
private fun PdfCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color,
    cardBg: Color,
    borderColor: Color
) {
    val context = LocalContext.current
    var pdfBitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    
    LaunchedEffect(item.uri) {
        withContext(Dispatchers.IO) {
            pdfBitmap = PdfPreviewGenerator.generateFirstPagePreview(context, item.uri)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Stacked paper silhouette instead of flat icon (Signature Detail)
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 56.dp)
        ) {
            // Back layer (representing stacked sheets)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp, top = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.LightGray.copy(alpha = 0.5f))
                    .border(0.5.dp, borderColor, RoundedCornerShape(4.dp))
            )
            // Front layer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 4.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White)
                    .border(0.5.dp, borderColor, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (pdfBitmap != null) {
                    Image(
                        bitmap = pdfBitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("📄", fontSize = 16.sp)
                }
            }
        }

        Spacer(Modifier.width(12.dp)) // 12dp gap

        Column(modifier = Modifier.weight(1f)) {
            val displayName = item.title ?: item.sourceFile.substringAfterLast('/')
            Text(
                text = displayName,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            CardChrome(species = species, secondaryText = secondaryText) {
                Text(
                    text = "12 pgs  •  2.4 MB",
                    fontSize = 8.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 7. APP / PLAY STORE LISTING (Icon forward Apple Wallet-style) ───────────
@Composable
private fun AppListingCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color,
    cardBg: Color,
    borderColor: Color
) {
    val cleanTitle = remember(item.title, item.uri) { cleanDisplayTitle(item.title, item.uri, "Play Store") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Store icon at leading edge
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AppListingColor.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cleanTitle.take(1).uppercase(),
                color = AppListingColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.width(12.dp))

        // Wallet style layout
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = cleanTitle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "⭐ 4.8  •  45 MB",
                fontSize = 9.sp,
                color = secondaryText
            )
            Spacer(Modifier.height(6.dp))
            CardChrome(species = species, secondaryText = secondaryText)
        }

        // Action at trailing edge
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AppListingColor,
            modifier = Modifier.height(24.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Text(
                    text = "GET",
                    color = Color.White,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── 8. LOCATION CARD CONTENT (Map tinted tone, abstract centered pin) ──────
@Composable
private fun LocationCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color,
    cardBg: Color
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Abstract flat map tone with center pin (no API keys or tiles needed)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFFEAF5EA)) // Map Tint tone
        ) {
            // Draw abstract road lines in canvas for a premium grid appearance
            Canvas(modifier = Modifier.fillMaxSize()) {
                val roadColor = Color(0xFFD4E6D4)
                drawLine(roadColor, Offset(0f, size.height * 0.3f), Offset(size.width, size.height * 0.4f), strokeWidth = 8f)
                drawLine(roadColor, Offset(size.width * 0.5f, 0f), Offset(size.width * 0.6f, size.height), strokeWidth = 8f)
                drawLine(roadColor, Offset(0f, size.height * 0.8f), Offset(size.width, size.height * 0.7f), strokeWidth = 6f)
                
                // Centered location halo
                drawCircle(LocationColor.copy(alpha = 0.2f), radius = 24.dp.toPx(), center = center)
            }
            
            // Centered pin
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = LocationColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = item.title ?: "Saved Location",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp
            )
            Spacer(Modifier.height(8.dp))
            CardChrome(species = species, secondaryText = secondaryText) {
                Text(
                    text = "0.8 miles away",
                    fontSize = 8.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 9. WEBSITE CARD CONTENT (Fallback web URLs) ─────────────────────────────
@Composable
private fun WebsiteCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color
) {
    val sourceName = remember(item.sourceApp, item.uri) { SourceResolver.getReadableAppName(item.sourceApp, item.uri) }
    val cleanTitle = remember(item.title, item.uri) { cleanDisplayTitle(item.title, item.uri, sourceName) }
    val cleanDomain = remember(item.uri) {
        val host = runCatching { android.net.Uri.parse(item.uri).host?.lowercase() }.getOrNull().orEmpty()
        host.removePrefix("www.")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.91f)
                .background(Color(0xFFEDE6DC)),
            contentAlignment = Alignment.Center
        ) {
            val hasPreview = remember(item.thumbnailPath, item.sourceFile, item.uri) {
                !item.thumbnailPath.isNullOrBlank() || item.sourceFile.isNotBlank() || (!item.uri.startsWith("http") && item.uri.isNotBlank())
            }
            if (hasPreview) {
                val model = when {
                    !item.thumbnailPath.isNullOrBlank() -> item.thumbnailPath
                    item.sourceFile.isNotBlank() -> item.sourceFile
                    else -> item.uri
                }
                val painter = rememberAsyncImagePainter(model = coil.request.ImageRequest.Builder(LocalContext.current).data(model).crossfade(400).build())
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                val domainLetter = cleanDomain.take(1).uppercase()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(WebsiteColor.copy(alpha = 0.2f), WebsiteColor.copy(alpha = 0.05f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(WebsiteColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = domainLetter.ifBlank { "W" },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = cleanTitle,
                fontSize = 12.sp,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            CardChrome(species = species, secondaryText = secondaryText) {
                Text(
                    text = "$cleanDomain  •  5 min read",
                    fontSize = 8.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ── 10. DOCUMENT/NOTE CARD CONTENT (Fallback notes, text summary) ────────────
@Composable
private fun GenericDocumentCardContent(
    item: StashItemWithVaultItem,
    species: ContentSpecies,
    primaryText: Color,
    secondaryText: Color
) {
    val sourceName = remember(item.sourceApp, item.uri) { SourceResolver.getReadableAppName(item.sourceApp, item.uri) }
    // A page of a Word/Excel/EPUB file has no title of its own; it is known by its file's name.
    val cleanTitle = remember(item.title, item.sourceFile, item.uri) {
        val fileName = item.sourceFile.substringAfterLast('/').takeIf {
            it.isNotBlank() && ContentSpecies.isOfficeDocument(item.itemType, item.mimeType, item.uri)
        }
        cleanDisplayTitle(item.title ?: fileName, item.uri, sourceName)
    }
    val cleanSummary = remember(item.ocrText) {
        val clean = item.ocrText.substringBefore("\n[").trim()
        if (clean.length > 80) clean.take(80) + "..." else clean
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Text(
            text = cleanTitle,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = primaryText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (cleanSummary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = cleanSummary,
                fontSize = 10.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontFamily = FontFamily.Serif,
                color = secondaryText,
                lineHeight = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        CardChrome(species = species, secondaryText = secondaryText)
    }
}

// ── Extraction Helper Stubs (to prevent duplicate code warnings) ─────────────
private fun extractYouTubeVideoId(url: String): String? {
    val patterns = listOf(
        Regex("(?:v=)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE),
        Regex("(?:youtu\\.be\\/)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE),
        Regex("(?:embed\\/)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE),
        Regex("(?:shorts\\/)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE)
    )
    for (regex in patterns) {
        val match = regex.find(url)
        if (match != null) return match.groupValues.getOrNull(1)
    }
    return null
}

private fun extractDuration(ocrText: String): String {
    val regex = Regex("\\b(\\d{1,2}:\\d{2})\\b")
    val match = regex.find(ocrText)
    return match?.groupValues?.getOrNull(1) ?: "10:15"
}

private fun extractPrice(ocrText: String): String? {
    val regex = Regex("(?:₹|Rs\\.?|INR)\\s*([\\d,]+(?:\\.\\d{2})?)", RegexOption.IGNORE_CASE)
    val match = regex.find(ocrText)
    return match?.value
}

private fun extractSpotifyDetails(title: String?): Pair<String, String> {
    val t = title.orEmpty()
    return when {
        t.contains(" - ") -> {
            val parts = t.split(" - ", limit = 2)
            parts[0].trim() to parts[1].trim()
        }
        t.contains(" by ") -> {
            val parts = t.split(" by ", limit = 2)
            parts[0].trim() to parts[1].trim()
        }
        else -> t.ifBlank { "Track" } to "Spotify Song"
    }
}

private fun extractInstagramCaption(uri: String): String? {
    if (uri.isBlank() || uri.startsWith("http")) return null
    val onInstaIndex = uri.indexOf(" on Instagram")
    if (onInstaIndex > 0) {
        val preceding = uri.substring(0, onInstaIndex).trim()
        if (preceding.startsWith("Watch this reel by", ignoreCase = true)) return null
        return preceding
    }
    return null
}

private fun cleanDisplayTitle(title: String?, uri: String?, sourceName: String): String {
    val t = title.orEmpty().trim()
    val u = uri.orEmpty().trim()
    
    if (t.isBlank() || t.startsWith("http")) {
        if (sourceName == "Instagram") {
            val caption = extractInstagramCaption(u)
            if (caption != null) return caption
            return if (u.contains("reel", ignoreCase = true)) "Instagram Reel" else "Instagram Post"
        }
        return when (sourceName) {
            "YouTube" -> "YouTube Video"
            "Spotify" -> "Spotify Track"
            "Amazon", "Flipkart" -> "Amazon Product"
            "Play Store" -> "Play Store App"
            else -> "Saved Item"
        }
    }
    return t
}
