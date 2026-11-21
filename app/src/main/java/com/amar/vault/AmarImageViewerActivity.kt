package com.amar.vault

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import com.amar.vault.ui.theme.AmarTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AmarImageViewerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "image_id"
        private const val EXTRA_URI = "image_uri"
        private const val EXTRA_TITLE = "image_title"
        private const val EXTRA_TEXT = "image_text"
        private const val EXTRA_TIMESTAMP = "image_timestamp"
        private const val EXTRA_IDS = "image_ids"
        private const val MOST_PHOTOS = 500

        fun open(context: Context, item: VaultItem) = open(context, listOf(item), 0)

        fun open(context: Context, items: List<VaultItem>, start: Int) {
            if (items.isEmpty()) return
            val at = start.coerceIn(0, items.lastIndex)
            val item = items[at]
            val from = (at - MOST_PHOTOS / 2).coerceIn(0, maxOf(0, items.size - MOST_PHOTOS))
            val window = items.subList(from, minOf(items.size, from + MOST_PHOTOS))
            val intent = Intent(context, AmarImageViewerActivity::class.java).apply {
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_URI, item.uri)
                putExtra(EXTRA_TITLE, item.title ?: item.sourceFile)
                putExtra(EXTRA_TEXT, item.ocrText)
                putExtra(EXTRA_TIMESTAMP, item.timestamp)
                putStringArrayListExtra(EXTRA_IDS, ArrayList(window.map { it.id }))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val first = VaultItem(
            id = intent.getStringExtra(EXTRA_ID) ?: uri,
            uri = uri,
            ocrText = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            lang = "en",
            itemType = ItemType.PHOTO,
            sourceFile = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis()),
            title = intent.getStringExtra(EXTRA_TITLE),
            mimeType = "image/*",
        )
        val ids = intent.getStringArrayListExtra(EXTRA_IDS)?.takeIf { it.isNotEmpty() } ?: arrayListOf(first.id)

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
            var items by remember { mutableStateOf(listOf(first)) }
            LaunchedEffect(Unit) {
                if (ids.size > 1) {
                    val loaded = withContext(Dispatchers.IO) {
                        runCatching { VaultDatabase.get(applicationContext).vaultDao().getByIds(ids) }.getOrDefault(emptyList())
                    }
                    val byId = loaded.associateBy { it.id }
                    val ordered = ids.mapNotNull { byId[it] }
                    if (ordered.size > 1 && ordered.any { it.id == first.id }) items = ordered
                }
            }
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                AmarTheme(darkTheme = true) {
                    key(items.size) {
                        FullScreenPhotoViewer(
                            items = items,
                            start = items.indexOfFirst { it.id == first.id }.coerceAtLeast(0),
                            onClose = { finish() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenPhotoViewer(
    items: List<VaultItem>,
    start: Int,
    onClose: () -> Unit
) {
    val lang = LocalAppLanguage.current
    val pager = rememberPagerState(initialPage = start) { items.size }
    var showOcrText by remember { mutableStateOf(false) }
    val item = items[pager.currentPage.coerceIn(0, items.lastIndex)]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pager,
            key = { items[it].id },
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val shown = items[page]
            com.amar.vault.ui.components.VaultPicture(
                address = shown.uri,
                contentDescription = shown.title ?: shown.sourceFile,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                failedIconSize = 56.dp,
                failedMessage = VaultStrings.pictureCannotOpen(lang)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = VaultStrings.back(lang),
                    tint = Color.White
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title ?: item.sourceFile.substringAfterLast('/'),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (items.size > 1) {
                    Text(
                        text = VaultStrings.viewerPosition(lang, pager.currentPage + 1, items.size),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }
            }

            if (item.ocrText.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    modifier = Modifier.clickable { showOcrText = !showOcrText }
                ) {
                    Text(
                        text = if (showOcrText) VaultStrings.hideText(lang) else VaultStrings.showText(lang),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (showOcrText && item.ocrText.isNotBlank()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xEE1E1E1E)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = VaultStrings.readFromPicture(lang),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF8EC5FF)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.ocrText,
                        color = Color.White,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
