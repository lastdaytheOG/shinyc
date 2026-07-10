package com.amar.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.AmarTheme
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.WarmBrown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AmarReaderActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URI = "reader_uri"
        private const val EXTRA_TITLE = "reader_title"
        private const val EXTRA_TEXT = "reader_text"

        fun open(context: Context, item: VaultItem) {
            val intent = Intent(context, AmarReaderActivity::class.java).apply {
                putExtra(EXTRA_URI, item.uri)
                putExtra(EXTRA_TITLE, item.title ?: item.sourceFile)
                putExtra(EXTRA_TEXT, item.ocrText)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Reader" }
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()

        setContent {
            AmarTheme {
                AmarReaderScreen(uri = uri, title = title, initialText = text, onBack = { finish() })
            }
        }
    }
}

@Composable
private fun AmarReaderScreen(uri: String, title: String, initialText: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember(uri, initialText) { mutableStateOf(initialText) }

    LaunchedEffect(uri, initialText) {
        if (text.isBlank() && uri.isNotBlank() && !uri.startsWith("share://")) {
            text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(uri))?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrDefault("")
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Cream)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Close", color = WarmBrown) }
            Text(
                text = title,
                color = CharcoalSoft,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = text.ifBlank { "No readable text found." },
            color = CharcoalSoft,
            fontSize = 17.sp,
            lineHeight = 26.sp,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
        )
    }
}
