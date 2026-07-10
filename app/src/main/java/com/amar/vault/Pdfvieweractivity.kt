package com.amar.vault

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight in-app PDF viewer using Android's built-in [PdfRenderer].
 *
 * Supports:
 * - Opening at a specific page via [EXTRA_PAGE]
 * - Search term display via [EXTRA_SEARCH_QUERY]
 * - Pinch-to-zoom
 * - Lazy page-by-page rendering (only renders visible pages)
 *
 * No external PDF library needed — uses the platform API available since API 21.
 *
 * Usage from code:
 * ```
 * PdfViewerActivity.open(context, contentUri, page = 5, searchQuery = "invoice")
 * ```
 *
 * Add to AndroidManifest.xml:
 * ```xml
 * <activity
 *     android:name=".PdfViewerActivity"
 *     android:exported="false" />
 * ```
 */
class PdfViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "pdf_uri"
        const val EXTRA_PAGE = "pdf_page"           // 1-indexed; 0 means restore saved page
        const val EXTRA_SEARCH_QUERY = "pdf_search"
        const val EXTRA_FILE_NAME = "pdf_file_name"

        /** Convenience launcher. */
        fun open(
            context: Context,
            uri: Uri,
            page: Int = 0,
            searchQuery: String = "",
            fileName: String = "",
        ) {
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_PAGE, page)
                putExtra(EXTRA_SEARCH_QUERY, searchQuery)
                putExtra(EXTRA_FILE_NAME, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriString = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val uri = Uri.parse(uriString)
        val requestedPage = intent.getIntExtra(EXTRA_PAGE, 0)
        val searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY) ?: ""
        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: ""
        val targetPage = if (requestedPage > 0) {
            requestedPage
        } else {
            getSharedPreferences("amar_pdf_positions", MODE_PRIVATE)
                .getInt(pdfPositionKey(uriString), 1)
        }

        setContent {
            MaterialTheme {
                PdfViewerScreen(
                    uri = uri,
                    targetPage = targetPage,
                    searchQuery = searchQuery,
                    fileName = fileName,
                    onBack = { finish() },
                )
            }
        }
    }
}

private fun pdfPositionKey(uri: String): String = "page_${uri.hashCode()}"

// ════════════════════════════════════════════════════════════════════════════════
// Composable viewer
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewerScreen(
    uri: Uri,
    targetPage: Int,
    searchQuery: String,
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Renderer state
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val listState = rememberLazyListState()

    // Open the PDF
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("Cannot open PDF")
                val pdfRenderer = PdfRenderer(fd)
                renderer = pdfRenderer
                pageCount = pdfRenderer.pageCount
            } catch (e: Exception) {
                errorMessage = "Failed to open PDF: ${e.message}"
            }
        }
    }

    // Scroll to target page once loaded
    LaunchedEffect(pageCount, targetPage) {
        if (pageCount > 0 && targetPage in 1..pageCount) {
            // targetPage is 1-indexed, list is 0-indexed
            listState.animateScrollToItem(targetPage - 1)
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            renderer?.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName.ifBlank { "PDF Viewer" },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = "Searching: \"$searchQuery\" • Page $targetPage",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Close")
                    }
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                errorMessage != null -> {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                }

                renderer == null -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pageCount) { pageIndex ->
                            PdfPageCard(
                                renderer = renderer!!,
                                pageIndex = pageIndex,
                                isTargetPage = pageIndex == targetPage - 1,
                                searchQuery = searchQuery,
                            )
                        }
                    }

                    // Page indicator
                    val currentPage = remember {
                        derivedStateOf {
                            val first = listState.firstVisibleItemIndex + 1
                            first
                        }
                    }

                    LaunchedEffect(currentPage.value) {
                        context.getSharedPreferences("amar_pdf_positions", Context.MODE_PRIVATE)
                            .edit()
                            .putInt(pdfPositionKey(uri.toString()), currentPage.value)
                            .apply()
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Page ${currentPage.value} of $pageCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════════
// Single PDF page — renders bitmap, supports pinch-to-zoom
// ════════════════════════════════════════════════════════════════════════════════

@Composable
private fun PdfPageCard(
    renderer: PdfRenderer,
    pageIndex: Int,
    isTargetPage: Boolean,
    searchQuery: String,
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Render this page's bitmap
    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(pageIndex)
                // Render at 2x for clarity on high-DPI screens
                val scaleFactor = 2
                val bmp = Bitmap.createBitmap(
                    page.width * scaleFactor,
                    page.height * scaleFactor,
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmap = bmp
            } catch (e: Exception) {
                // Page render failed — leave bitmap null
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isTargetPage)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isTargetPage)
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(
                    MaterialTheme.colorScheme.primary
                )
            )
        else null,
    ) {
        Column {
            // Page number header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Page ${pageIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isTargetPage) FontWeight.Bold else FontWeight.Normal,
                    color = if (isTargetPage)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isTargetPage && searchQuery.isNotBlank()) {
                    Text(
                        text = "Match found",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Page content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (bitmap != null) bitmap!!.width.toFloat() / bitmap!!.height
                        else 0.707f // A4 ratio fallback
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Page ${pageIndex + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
