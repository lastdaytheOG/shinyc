package com.amar.vault

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.indexing.DocumentRelink
import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import com.amar.vault.ui.components.search.withQueryWordsMarked
import com.amar.vault.ui.theme.AmarTheme
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
 */
class PdfViewerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "pdf_uri"
        const val EXTRA_PAGE = "pdf_page"
        const val EXTRA_SEARCH_QUERY = "pdf_search"
        const val EXTRA_MATCH_TEXT = "pdf_match_text"
        const val EXTRA_FILE_NAME = "pdf_file_name"

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
            val prefs by ScanPreferences.prefsFlow(applicationContext).collectAsState(initial = null)
            val lang = remember(prefs?.language) {
                when (prefs?.language) {
                    "hi" -> AppLanguage.HINDI
                    "en" -> AppLanguage.ENGLISH
                    else -> {
                        val locale = resources.configuration.locales[0]
                        if (locale.language.startsWith("hi")) AppLanguage.HINDI else AppLanguage.ENGLISH
                    }
                }
            }
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                AmarTheme {
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
    val lang = LocalAppLanguage.current

    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var password by remember { mutableStateOf<String?>(null) }
    var tries by remember { mutableIntStateOf(0) }
    var lockedNote by remember { mutableStateOf<String?>(null) }

    var openFrom by remember { mutableStateOf(uri) }
    var canFind by remember { mutableStateOf(false) }
    var findNote by remember { mutableStateOf<String?>(null) }
    val finder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked: Uri? ->
        if (picked == null) return@rememberLauncherForActivityResult
        findNote = VaultStrings.checkingFile(lang)
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
                    findNote = VaultStrings.viewerCannotRead(lang)
                else -> findNote = VaultStrings.viewerAnotherFile(lang, fileName)
            }
        }
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (targetPage - 1).coerceAtLeast(0))
    val renderLock = remember { Mutex() }
    var broughtIntoView by remember { mutableStateOf(false) }

    LaunchedEffect(openFrom, tries) {
        errorMessage = null
        canFind = false
        withContext(Dispatchers.IO) {
            try {
                val fd = context.contentResolver.openFileDescriptor(openFrom, "r")
                    ?: throw IllegalStateException("Cannot open PDF")
                val pdfRenderer = try {
                    PdfRenderer(fd)
                } catch (locked: SecurityException) {
                    fd.close()
                    val fingerprint = com.amar.vault.indexing.PdfSourceReuseCache.fingerprint(context, openFrom)
                    val key = password ?: fingerprint?.let(com.amar.vault.indexing.PdfPasswords::forViewing)
                    val copy = key?.let { com.amar.vault.indexing.LockedPdfCopy.make(context, openFrom, it) }
                    if (copy == null) {
                        lockedNote = if (password == null) VaultStrings.viewerLockedNote(lang)
                        else VaultStrings.viewerWrongPassword(lang)
                        return@withContext
                    }
                    lockedNote = null
                    if (fingerprint != null) com.amar.vault.indexing.PdfPasswords.opens(fingerprint, key)
                    val unlocked = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
                    copy.delete()
                    PdfRenderer(unlocked)
                }
                pageCount = pdfRenderer.pageCount
                renderer = pdfRenderer
            } catch (e: Exception) {
                errorMessage = when (e) {
                    is SecurityException, is java.io.FileNotFoundException -> {
                        canFind = runCatching {
                            DocumentRelink.get(context).documentsAt(openFrom.toString()).isNotEmpty()
                        }.getOrDefault(false)
                        if (canFind) VaultStrings.viewerFileGone(lang) else VaultStrings.viewerFileGoneAddAgain(lang)
                    }
                    is java.io.IOException, is IllegalArgumentException -> VaultStrings.viewerDamaged(lang)
                    else -> VaultStrings.viewerCouldNotOpen(lang, e.message.orEmpty())
                }
            } catch (tooBig: OutOfMemoryError) {
                errorMessage = VaultStrings.viewerTooBig(lang)
            }
        }
    }

    LaunchedEffect(pageCount, targetPage) {
        if (pageCount > 0 && targetPage in 1..pageCount && listState.firstVisibleItemIndex != targetPage - 1) {
            listState.scrollToItem(targetPage - 1)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            renderer?.close()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = fileName.ifBlank { VaultStrings.pdfViewerTitle(lang) },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = VaultStrings.foundOnPageNote(lang, searchQuery, targetPage),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(VaultStrings.close(lang))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val spaceAfterLastPage = maxHeight * 0.4f
            when {
                errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp
                        )
                        if (canFind) {
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { finder.launch(arrayOf("application/pdf")) },
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(VaultStrings.findTheFile(lang)) }
                        }
                        findNote?.let { note ->
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = note,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                lockedNote != null -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = lockedNote!!,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp
                        )
                        PdfPasswordField(buttonText = VaultStrings.open(lang), lang = lang) { typed ->
                            password = typed
                            tries++
                        }
                    }
                }

                renderer == null -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
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
                        color = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = VaultStrings.pageOf(lang, currentPage.value, pageCount),
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

@Composable
private fun PdfPageCard(
    renderer: PdfRenderer,
    renderLock: Mutex,
    pageIndex: Int,
    isTargetPage: Boolean,
    searchQuery: String,
    matchText: String,
    onMatchAt: (Float) -> Unit = {},
) {
    val lang = LocalAppLanguage.current
    val isMatch = isTargetPage && searchQuery.isNotBlank()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
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

    LaunchedEffect(pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                bitmap = renderLock.withLock {
                    val page = renderer.openPage(pageIndex)
                    try {
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
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            if (isMatch) 1.5.dp else 1.dp,
            if (isMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isMatch) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = VaultStrings.pageBadge(lang, pageIndex + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isMatch) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isMatch) {
                    Text(
                        text = VaultStrings.matchOnThisPage(lang),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            if (isMatch && matchText.isNotBlank()) {
                Text(
                    text = remember(matchText, searchQuery) { withQueryWordsMarked(matchText, searchQuery) },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(
                        if (bitmap != null) bitmap!!.width.toFloat() / bitmap!!.height
                        else 0.707f
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
                            contentDescription = VaultStrings.pageBadge(lang, pageIndex + 1),
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
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
