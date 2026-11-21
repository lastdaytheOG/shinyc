package com.amar.vault

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import com.amar.vault.ui.theme.AmarTheme

class AmarImageViewerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_ID = "image_id"
        private const val EXTRA_URI = "image_uri"
        private const val EXTRA_TITLE = "image_title"
        private const val EXTRA_TEXT = "image_text"
        private const val EXTRA_TIMESTAMP = "image_timestamp"

        fun open(context: Context, item: VaultItem) {
            val intent = Intent(context, AmarImageViewerActivity::class.java).apply {
                putExtra(EXTRA_ID, item.id)
                putExtra(EXTRA_URI, item.uri)
                putExtra(EXTRA_TITLE, item.title ?: item.sourceFile)
                putExtra(EXTRA_TEXT, item.ocrText)
                putExtra(EXTRA_TIMESTAMP, item.timestamp)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val item = VaultItem(
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
                AmarTheme(darkTheme = true) {
                    FullScreenPhotoViewer(item = item, onClose = { finish() })
                }
            }
        }
    }
}

@Composable
private fun FullScreenPhotoViewer(
    item: VaultItem,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lang = LocalAppLanguage.current
    var showOcrText by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        com.amar.vault.ui.components.VaultPicture(
            address = item.uri,
            contentDescription = item.title ?: item.sourceFile,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            failedIconSize = 56.dp,
            failedMessage = VaultStrings.pictureCannotOpen(lang)
        )

        // Top Overlay Bar
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
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = VaultStrings.back(lang),
                    tint = Color.White
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = item.title ?: item.sourceFile.substringAfterLast('/'),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

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

        // Bottom OCR Excerpt Sheet (if toggled)
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
                        color = Color(0xFFA8C7FA),
                        letterSpacing = 0.5.sp
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
