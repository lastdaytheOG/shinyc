package com.amar.vault

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.rememberAsyncImagePainter
import com.amar.vault.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectibleDetailView(
    item: StashItemWithVaultItem,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onNoteChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /**
     * False for an item that is in the vault and was never saved to a folder: it has no
     * favourite to toggle, and what its delete button does is take it out of the vault.
     */
    isSaved: Boolean = true,
) {
    val context = LocalContext.current
    val species = remember(item) { ContentSpecies.classify(item) }
    
    // Staggered metadata entrance animation state
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Stagger metadata to fade-in after media transition has finished/settled (approx 250ms)
        delay(220)
        contentVisible = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(species.dotColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = species.label.uppercase(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = CharcoalSoft,
                            letterSpacing = 1.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = WarmBrownDark)
                    }
                },
                actions = {
                    IconButton(onClick = { ContentOpenManager.share(context, item) }) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = WarmBrownDark)
                    }
                    // A favourite is something a saved item has. On anything else the heart
                    // was a button that did nothing.
                    if (isSaved) IconButton(onClick = onFavoriteToggle) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Favorite",
                            tint = if (item.isFavorite) species.dotColor else WarmBrown
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)
            )
        },
        containerColor = Cream,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. Primary - Media Area (Always the largest, highest-contrast element)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1C1A18))
            ) {
                when (species) {
                    ContentSpecies.REEL -> DetailReelMedia(item)
                    ContentSpecies.PDF -> DetailPdfMedia(item)
                    ContentSpecies.LOCATION -> DetailLocationMedia(item)
                    ContentSpecies.AUDIO -> DetailAudioMedia(item)
                    ContentSpecies.YOUTUBE_VIDEO -> DetailYouTubeMedia(item)
                    ContentSpecies.PRODUCT -> DetailProductMedia(item)
                    ContentSpecies.SCREENSHOT -> DetailScreenshotMedia(item)
                    else -> DetailDefaultMedia(item)
                }
            }

            // 2. Secondary & Tertiary Info Panel - Staggered fade in
            AnimatedVisibility(
                visible = contentVisible,
                enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
                    initialOffsetY = { 20 },
                    animationSpec = tween(300)
                ),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CreamLight)
                        .padding(20.dp)
                ) {
                    // Title (Secondary - Medium weight, max 2 lines)
                    val displayName = remember(item) {
                        item.title ?: item.sourceFile.substringAfterLast('/')
                    }
                    Text(
                        text = displayName.ifBlank { "${species.label} Saved Item" },
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        color = CharcoalSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 22.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    // Metadata attributes (Tertiary - specific details)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        DetailMetadataPanel(item, species, onNoteChange)

                        Spacer(Modifier.height(20.dp))

                        // Action Panel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { ContentOpenManager.openOriginal(context, item) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = species.dotColor),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Open Original", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            
                            OutlinedButton(
                                onClick = onDelete,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (isSaved) "Delete Item" else "Remove from vault", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── DETAIL VIDEO PLAYER (REEL) ──────────────────────────────────────────────
@Composable
private fun DetailReelMedia(item: StashItemWithVaultItem) {
    val context = LocalContext.current
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(item.uri)))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            volume = 0f // Muted auto-playing preview
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false } },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("Auto-playing preview", color = Color.White, fontSize = 10.sp)
        }
    }
}

// ── DETAIL PDF PAGE FLIP ────────────────────────────────────────────────────
@Composable
private fun DetailPdfMedia(item: StashItemWithVaultItem) {
    val context = LocalContext.current
    
    val pageCount = remember(item.uri) {
        runCatching {
            val pfd = PdfPreviewGenerator.open(context, item.uri) ?: return@runCatching 0
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        }.getOrDefault(1)
    }

    val pagerState = rememberPagerState(pageCount = { pageCount })

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
        ) { pageIndex ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(0.5.dp, Color.LightGray, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                PdfDetailViewer(context, item.uri, pageIndex)
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Page ${pagerState.currentPage + 1} of $pageCount (Swipe to flip)",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PdfDetailViewer(context: Context, uriString: String, pageIndex: Int) {
    var bitmap by remember(uriString, pageIndex) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uriString, pageIndex) {
        withContext(Dispatchers.IO) {
            bitmap = renderPdfPage(context, uriString, pageIndex)
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "PDF Page $pageIndex",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    } else {
        CircularProgressIndicator(color = PdfColor)
    }
}

private fun renderPdfPage(context: Context, uriString: String, pageIndex: Int): Bitmap? {
    if (uriString.isBlank()) return null
    return try {
        val pfd = PdfPreviewGenerator.open(context, uriString) ?: return null
        pfd.use {
            val renderer = PdfRenderer(pfd)
            if (pageIndex < renderer.pageCount) {
                val page = renderer.openPage(pageIndex)
                val targetWidth = 720
                val aspectRatio = page.height.toFloat() / page.width.toFloat()
                val targetHeight = (targetWidth * aspectRatio).toInt().coerceIn(100, 1600)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                renderer.close()
                bitmap
            } else {
                renderer.close()
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

// ── DETAIL LOCATION ─────────────────────────────────────────────────────────
@Composable
private fun DetailLocationMedia(item: StashItemWithVaultItem) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEAF5EA)), // Map tinted tint background
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val roadColor = Color(0xFFD4E6D4)
            drawLine(roadColor, Offset(0f, size.height * 0.2f), Offset(size.width, size.height * 0.35f), strokeWidth = 12f)
            drawLine(roadColor, Offset(size.width * 0.4f, 0f), Offset(size.width * 0.55f, size.height), strokeWidth = 12f)
            drawLine(roadColor, Offset(0f, size.height * 0.75f), Offset(size.width, size.height * 0.6f), strokeWidth = 8f)
            drawCircle(LocationColor.copy(alpha = 0.2f), radius = 64.dp.toPx(), center = center)
        }
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = LocationColor,
                modifier = Modifier.size(54.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text("Saved Location Pin", fontWeight = FontWeight.Bold, color = CharcoalSoft)
        }
    }
}

// ── DETAIL AUDIO ────────────────────────────────────────────────────────────
@Composable
private fun DetailAudioMedia(item: StashItemWithVaultItem) {
    val details = remember(item.title) { extractSpotifyDetails(item.title) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Large album art
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF282828))
                .shadow(8.dp)
        ) {
            val painter = rememberAsyncImagePainter(model = item.uri)
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(Modifier.height(20.dp))

        // Static Waveform Visualizer
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val bars = listOf(14, 28, 16, 38, 20, 26, 12, 34, 18, 30, 24, 38, 16, 28, 14)
            bars.forEach { h ->
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(h.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(SpotifyColor)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "0:00 / 3:42",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── DETAIL YOUTUBE ──────────────────────────────────────────────────────────
@Composable
private fun DetailYouTubeMedia(item: StashItemWithVaultItem) {
    val videoId = remember(item.uri) { extractYouTubeVideoId(item.uri) }
    val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val painter = rememberAsyncImagePainter(model = thumbnailUrl)
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play Video",
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

// ── DETAIL PRODUCT ──────────────────────────────────────────────────────────
@Composable
private fun DetailProductMedia(item: StashItemWithVaultItem) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        val painter = rememberAsyncImagePainter(model = item.uri)
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

// ── DETAIL SCREENSHOT (Full-bleed Zoomable) ──────────────────────────────────
@Composable
private fun DetailScreenshotMedia(item: StashItemWithVaultItem) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val painter = rememberAsyncImagePainter(model = item.uri)
        Image(
            painter = painter,
            contentDescription = "Screenshot Zoomable View",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offsetX += pan.x * scale
                        offsetY += pan.y * scale
                    }
                }
        )
    }
}

// ── DETAIL DEFAULT ──────────────────────────────────────────────────────────
@Composable
private fun DetailDefaultMedia(item: StashItemWithVaultItem) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFEDE6DC)),
        contentAlignment = Alignment.Center
    ) {
        val painter = rememberAsyncImagePainter(model = item.uri)
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    }
}

// ── METADATA PANEL COMPONENT ────────────────────────────────────────────────
@Composable
private fun DetailMetadataPanel(item: StashItemWithVaultItem, species: ContentSpecies, onNoteChange: (String) -> Unit) {
    val context = LocalContext.current
    var extractedMetadata by remember { mutableStateOf<List<VaultMetadata>>(emptyList()) }
    // How many pages the document has, as counted when it was read; null when that is not known.
    var pageCount by remember { mutableStateOf<Int?>(null) }
    var isEditingNote by remember { mutableStateOf(false) }
    var editNoteText by remember { mutableStateOf(item.userNote ?: "") }

    LaunchedEffect(item.vaultItemId) {
        withContext(Dispatchers.IO) {
            val db = VaultDatabase.get(context)
            extractedMetadata = db.vaultMetadataDao().getByItemId(item.vaultItemId)
            pageCount = db.vaultDocumentDao().getById(item.parentDocumentId ?: item.vaultItemId)?.pageCount
                // A document read before pages were counted: the file itself knows.
                ?: if (species != ContentSpecies.PDF) null else runCatching {
                    val uri = com.amar.vault.share.open.ShareContentUri.resolve(context, item.uri) ?: return@runCatching null
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { file ->
                        android.graphics.pdf.PdfRenderer(file).use { it.pageCount }
                    }
                }.getOrNull()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // User Note Section (if present or editing)
        if (isEditingNote || !item.userNote.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(species.dotColor.copy(alpha = 0.1f))
                    .border(1.dp, species.dotColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { if (!isEditingNote) isEditingNote = true }
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📝 Your Note",
                            color = species.dotColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        if (isEditingNote) {
                            Text(
                                text = "Save",
                                color = species.dotColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    isEditingNote = false
                                    onNoteChange(editNoteText)
                                }.padding(4.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (isEditingNote) {
                        OutlinedTextField(
                            value = editNoteText,
                            onValueChange = { editNoteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = CharcoalSoft),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = species.dotColor,
                                unfocusedBorderColor = species.dotColor.copy(alpha = 0.5f)
                            )
                        )
                    } else {
                        Text(
                            text = item.userNote ?: "",
                            fontSize = 13.sp,
                            color = CharcoalSoft,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // A file added from the phone's own storage came from no app: its address is the
        // storage provider's, and naming an app from that gave "Com".
        if (item.sourceApp.isBlank() && !item.uri.startsWith("http", ignoreCase = true)) {
            DetailRow(label = "Added from", value = "Files on this phone")
        } else {
            DetailRow(label = "Source App", value = SourceResolver.getReadableAppName(item.sourceApp, item.uri))
        }
        DetailRow(label = "Date Collected", value = formatFullTime(item.savedAt))

        if (item.category.isNotBlank()) {
            DetailRow(label = "Vault Category", value = item.category)
        }

        // Render species specific items
        when (species) {
            // Only what the item itself says. These rows used to fall back on made-up values
            // — "₹14,999", "10:15", "12 pages" — shown as if they were facts about the item.
            ContentSpecies.PRODUCT -> {
                extractPrice(item.ocrText)?.let { DetailRow(label = "Price", value = it) }
            }
            ContentSpecies.YOUTUBE_VIDEO -> {
                extractDuration(item.ocrText)?.let { DetailRow(label = "Video Duration", value = it) }
            }
            ContentSpecies.PDF -> {
                pageCount?.takeIf { it > 0 }?.let { DetailRow(label = "Page Count", value = if (it == 1) "1 page" else "$it pages") }
                DetailRow(label = "Format", value = "PDF Document")
            }
            else -> {}
        }

        if (extractedMetadata.isNotEmpty()) {
            Divider(color = CreamDark, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "EXTRACTED DETAILS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = WarmBrown,
                letterSpacing = 1.sp
            )
            extractedMetadata.forEach { meta ->
                DetailRow(label = meta.type, value = meta.value)
            }
        }

        val ocrClean = item.ocrText.trim()
        if (ocrClean.isNotBlank()) {
            Divider(color = CreamDark, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
            var textExpanded by remember { mutableStateOf(false) }
            TextButton(
                onClick = { textExpanded = !textExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (textExpanded) "Hide Extracted Text" else "Show Extracted Text",
                    color = species.dotColor,
                    fontWeight = FontWeight.Bold
                )
            }
            if (textExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CreamDark.copy(alpha = 0.3f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = ocrClean,
                        fontSize = 13.sp,
                        color = CharcoalSoft,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 12.sp, color = WarmBrownDark, fontWeight = FontWeight.Medium)
        Text(text = value, fontSize = 13.sp, color = CharcoalSoft, fontWeight = FontWeight.Bold)
    }
}

// ── UTILITY HELPERS ──────────────────────────────────────────────────────────
private fun formatFullTime(timestamp: Long): String {
    val date = Date(timestamp)
    val sdf = SimpleDateFormat("MMMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
    return sdf.format(date)
}

private fun extractYouTubeVideoId(url: String): String? {
    val pattern = "^(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com\\/(?:[^\\/\\n\\s]+\\/\\S+\\/|(?:v|e(?:mbed)?)\\/|\\S*?[?&]v=)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})"
    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    return regex.find(url)?.groupValues?.getOrNull(1)
}

private fun extractDuration(ocrText: String): String? {
    val regex = Regex("\\b(\\d{1,2}:\\d{2})\\b")
    val match = regex.find(ocrText)
    return match?.groupValues?.getOrNull(1)
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
