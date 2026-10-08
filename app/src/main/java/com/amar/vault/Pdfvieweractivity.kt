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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.indexing.DocumentRelink
import com.amar.vault.ui.components.search.withQueryWordsMarked
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.PdfColor
import com.amar.vault.ui.theme.PdfWash
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Lightweight in-app PDF viewer using Android's built-in [PdfRenderer].
 *
 * Supports:
 * - Opening at a specific page via [EXTRA_PAGE]
 * - Search term display via [EXTRA_SEARCH_QUERY], with the words it was found in via [EXTRA_MATCH_TEXT]
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
        const val EXTRA_MATCH_TEXT = "pdf_match_text" // the words around the match on EXTRA_PAGE
        const val EXTRA_FILE_NAME = "pdf_file_name"

        /** Convenience launcher. */
        fun open(
            context: Context,
            uri: Uri,
            page: Int = 0,
            searchQuery: String = "",
            fileName: String = "",
            matchText: String = "",
        ) {
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_PAGE, page)
                putExtra(EXTRA_SEARCH_QUERY, searchQuery)
                putExtra(EXTRA_MATCH_TEXT, matchText)
                putExtra(EXTRA_FILE_NAME, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A list of results that was on screen before the file was found again still holds
        // the address it had then.
        val uriString = DocumentRelink.currentAddress(intent.getStringExtra(EXTRA_URI) ?: run { finish(); return })
        val uri = Uri.parse(uriString)
        val requestedPage = intent.getIntExtra(EXTRA_PAGE, 0)
        val searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY) ?: ""
        val matchText = intent.getStringExtra(EXTRA_MATCH_TEXT) ?: ""
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
                    matchText = matchText,
                    fileName = fileName,
                    onBack = { finish() },
                )
            }
        }
    }
}

private fun pdfPositionKey(uri: String): String = "page_${uri.hashCode()}"

/** The marker drawn over a found word on a page: see-through, so the word stays readable. */
private val MatchMarker = Color(0x66FFD54F)

/** Reads the words off a rendered page, with where each one is. */
private object PageReader {
    private val latin by lazy {
        com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }
    private val devanagari by lazy {
        com.google.mlkit.vision.text.TextRecognition.getClient(
            com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build()
        )
    }

    /** [lookingFor] decides the reader: the Devanagari one when the words sought are in that script. */
    suspend fun words(page: Bitmap, lookingFor: String): List<WordOnPage> {
        val client = if (lookingFor.any { it in '\u0900'..'\u097F' }) devanagari else latin
        val found = client.process(com.google.mlkit.vision.common.InputImage.fromBitmap(page, 0)).await()
        var line = 0
        return found.textBlocks.flatMap { it.lines }.flatMap { onLine ->
            val number = line++
            onLine.elements.mapNotNull { word ->
                word.boundingBox?.let { box ->
                    WordOnPage(word.text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(), number)
                }
            }
        }
    }
}

/** What the viewer says when the file it was asked to show is not there. */
internal object FileGoneWords {
    const val GONE =
        "This file is no longer where it was when you added it: it was moved, renamed or deleted. " +
            "Its text can still be searched. Show the app where the file is now and it opens again."

    /** For a file the vault has no document for, which cannot be found again from here. */
    const val GONE_ADD_AGAIN =
        "This file is no longer where it was when you added it: it was moved, renamed or deleted. " +
            "Add it again from Import documents."

    const val CANNOT_READ = "That file could not be read. Choose the file you added."

    fun anotherFile(name: String): String =
        "That is a different file: its contents are not the ones stored" +
            (if (name.isBlank()) "." else " for \"$name\".") + " Choose the file you added."
}

// ════════════════════════════════════════════════════════════════════════════════
// Composable viewer
// ════════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfViewerScreen(
    uri: Uri,
    targetPage: Int,
    searchQuery: String,
    matchText: String,
    fileName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Renderer state
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Where the file is opened from: the address it was added at, until that leads nowhere
    // and the user shows where the file is now.
    var openFrom by remember { mutableStateOf(uri) }
    // True when the file is gone from a document the vault knows, which can then be found again.
    var canFind by remember { mutableStateOf(false) }
    var findNote by remember { mutableStateOf<String?>(null) }
    val finder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked == null) return@rememberLauncherForActivityResult
        findNote = "Checking the file…"
        scope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                val relink = DocumentRelink.get(context)
                relink.documentsAt(openFrom.toString()).map { relink.relink(it, picked) }
            }
            when {
                outcomes.any { it is DocumentRelink.Outcome.Found } -> {
                    findNote = null
                    openFrom = picked
                }
                outcomes.any { it is DocumentRelink.Outcome.CannotRead } ->
                    findNote = FileGoneWords.CANNOT_READ
                else -> findNote = FileGoneWords.anotherFile(fileName)
            }
        }
    }

    // The list starts ON the requested page (targetPage is 1-indexed, the list 0-indexed):
    // arriving from a search result must show that page at once, not page 1 and then a scroll
    // through every page before it.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (targetPage - 1).coerceAtLeast(0))
    // PdfRenderer allows one open page at a time. Pages that come on screen together used to
    // render at once, and the one that lost was left as a spinner for good.
    val renderLock = remember { Mutex() }
    // Set once the words a search found have been brought into view on the page it opened on.
    var broughtIntoView by remember { mutableStateOf(false) }

    // Open the PDF
    LaunchedEffect(openFrom) {
        errorMessage = null
        canFind = false
        withContext(Dispatchers.IO) {
            try {
                val fd = context.contentResolver.openFileDescriptor(openFrom, "r")
                    ?: throw IllegalStateException("Cannot open PDF")
                val pdfRenderer = PdfRenderer(fd)
                // The count first: the list is built the moment the renderer is set, and a list
                // built with no pages yet would drop the starting page above.
                pageCount = pdfRenderer.pageCount
                renderer = pdfRenderer
            } catch (e: Exception) {
                errorMessage = when (e) {
                    // A document added with the picker stays where it was; the vault holds its
                    // text and a link to it, and the link breaks if the file goes away.
                    is SecurityException, is java.io.FileNotFoundException -> {
                        canFind = runCatching {
                            DocumentRelink.get(context).documentsAt(openFrom.toString()).isNotEmpty()
                        }.getOrDefault(false)
                        if (canFind) FileGoneWords.GONE else FileGoneWords.GONE_ADD_AGAIN
                    }
                    is java.io.IOException, is IllegalArgumentException ->
                        "This file is damaged or is not a PDF, so it cannot be shown. Its text can still be searched."
                    else -> "The file could not be opened: ${e.message}"
                }
            }
        }
    }

    // Belt and braces for the starting page, without animation.
    LaunchedEffect(pageCount, targetPage) {
        if (pageCount > 0 && targetPage in 1..pageCount && listState.firstVisibleItemIndex != targetPage - 1) {
            listState.scrollToItem(targetPage - 1)
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            renderer?.close()
        }
    }

    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName.ifBlank { "PDF Viewer" },
                            color = CharcoalSoft,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = "\"$searchQuery\" found on page $targetPage",
                                style = MaterialTheme.typography.labelMedium,
                                color = WarmBrownDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Close", color = WarmBrown)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Room below the last page, so that it too can be brought to the top of the screen:
            // without it a match on a document's final page landed part-way down, under the
            // end of the page before it.
            val spaceAfterLastPage = maxHeight * 0.4f
            when {
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Said calmly: a file that was moved is not something that went wrong in the app.
                        Text(text = errorMessage!!, color = CharcoalSoft, fontSize = 15.sp, lineHeight = 22.sp)
                        if (canFind) {
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { finder.launch(arrayOf("application/pdf")) },
                                colors = ButtonDefaults.buttonColors(containerColor = WarmBrownDark),
                            ) { Text("Find the file", color = Cream) }
                        }
                        findNote?.let { note ->
                            Spacer(Modifier.height(12.dp))
                            Text(text = note, color = WarmBrownDark, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                renderer == null -> {
                    CircularProgressIndicator(
                        color = WarmBrown,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = spaceAfterLastPage),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(pageCount) { pageIndex ->
                            PdfPageCard(
                                renderer = renderer!!,
                                renderLock = renderLock,
                                pageIndex = pageIndex,
                                isTargetPage = pageIndex == targetPage - 1,
                                searchQuery = searchQuery,
                                matchText = matchText,
                                onMatchAt = { yInCard ->
                                    // Once, and only if the words are not already in plain sight:
                                    // a page is taller than the screen, and a match low on it was
                                    // below the fold with nothing to say so.
                                    if (!broughtIntoView) {
                                        broughtIntoView = true
                                        val layout = listState.layoutInfo
                                        val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                                        val wanted = (yInCard - viewport * 0.35f).toInt()
                                        if (wanted > 0) scope.launch { listState.animateScrollToItem(pageIndex, wanted) }
                                    }
                                },
                            )
                        }
                    }

                    // Page indicator: the page under the middle of the screen. The first visible
                    // row is the page BEFORE the one being read whenever its last lines still show.
                    val currentPage = remember {
                        derivedStateOf {
                            val layout = listState.layoutInfo
                            val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                            val reading = layout.visibleItemsInfo
                                .firstOrNull { it.offset <= middle && it.offset + it.size > middle }
                            (reading?.index ?: listState.firstVisibleItemIndex) + 1
                        }
                    }

                    LaunchedEffect(currentPage.value) {
                        context.getSharedPreferences("amar_pdf_positions", Context.MODE_PRIVATE)
                            .edit()
                            .putInt(pdfPositionKey(openFrom.toString()), currentPage.value)
                            .apply()
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = CharcoalSoft.copy(alpha = 0.85f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = "Page ${currentPage.value} of $pageCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = Cream,
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
    renderLock: Mutex,
    pageIndex: Int,
    isTargetPage: Boolean,
    searchQuery: String,
    matchText: String,
    /** Told where the match is, in pixels down from the top of this card, once it is found. */
    onMatchAt: (Float) -> Unit = {},
) {
    // The page a search opened on is marked only when the search found words on it.
    val isMatch = isTargetPage && searchQuery.isNotBlank()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    // Where the words are on the page itself. The page is a picture here, whether the PDF has a
    // text layer or is a scan, so the words are read off the picture, with their places.
    var match by remember { mutableStateOf(PageMatch.NONE) }
    var pictureTop by remember { mutableFloatStateOf(0f) }
    var pictureHeight by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(bitmap, isMatch) {
        val page = bitmap ?: return@LaunchedEffect
        if (!isMatch) return@LaunchedEffect
        match = withContext(Dispatchers.Default) {
            runCatching {
                PageMatches.locate(PageReader.words(page, searchQuery + " " + matchText), queryWordsOf(searchQuery), matchText)
            }.getOrDefault(PageMatch.NONE)
        }
    }
    LaunchedEffect(match, pictureHeight) {
        val focus = match.focus ?: return@LaunchedEffect
        val page = bitmap ?: return@LaunchedEffect
        if (pictureHeight > 0f) onMatchAt(pictureTop + (focus.top + focus.bottom) / 2f / page.height * pictureHeight)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Render this page's bitmap
    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                bitmap = renderLock.withLock {
                    val page = renderer.openPage(pageIndex)
                    try {
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
                        bmp
                    } finally {
                        page.close()
                    }
                }
            } catch (e: Exception) {
                // Page render failed — leave bitmap null
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(if (isMatch) 1.5.dp else 1.dp, if (isMatch) PdfColor else CreamDark),
    ) {
        Column {
            // Page number header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isMatch) PdfWash else CreamLight)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Page ${pageIndex + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Normal,
                    color = if (isMatch) PdfColor else WarmBrownDark,
                )
                if (isMatch) {
                    Text(
                        text = "Match on this page",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = PdfColor,
                    )
                }
            }
            // The words the search found, so the eye knows what to look for on the page.
            if (isMatch && matchText.isNotBlank()) {
                Text(
                    text = remember(matchText, searchQuery) { withQueryWordsMarked(matchText, searchQuery) },
                    color = WarmBrownDark,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PdfWash)
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }

            // Page content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (bitmap != null) bitmap!!.width.toFloat() / bitmap!!.height
                        else 0.707f // A4 ratio fallback
                    )
                    .onGloballyPositioned {
                        pictureTop = it.positionInParent().y
                        pictureHeight = it.size.height.toFloat()
                    }
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
                    // The page and the marks on it move and zoom as one.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    ) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = "Page ${pageIndex + 1}",
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (match.marks.isNotEmpty()) {
                            val page = bitmap!!
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val sx = size.width / page.width
                                val sy = size.height / page.height
                                val pad = 2.dp.toPx()
                                for (word in match.marks) {
                                    drawRoundRect(
                                        color = MatchMarker,
                                        topLeft = Offset(word.left * sx - pad, word.top * sy - pad),
                                        size = Size((word.right - word.left) * sx + 2 * pad, (word.bottom - word.top) * sy + 2 * pad),
                                        cornerRadius = CornerRadius(pad, pad),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    CircularProgressIndicator(color = WarmBrown, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
