package com.amar.vault

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
            AmarTheme {
                PhotoViewer(photo = item, photos = listOf(item), onClose = { finish() })
            }
        }
    }
}
