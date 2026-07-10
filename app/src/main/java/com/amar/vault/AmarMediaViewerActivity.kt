package com.amar.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.amar.vault.ui.theme.AmarTheme
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.WarmBrown

class AmarMediaViewerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URI = "media_uri"
        private const val EXTRA_TITLE = "media_title"
        private const val EXTRA_IS_VIDEO = "media_is_video"

        fun open(context: Context, item: VaultItem, isVideo: Boolean) {
            val intent = Intent(context, AmarMediaViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, item.uri)
                putExtra(EXTRA_TITLE, item.title ?: item.sourceFile)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, true)

        setContent {
            AmarTheme {
                AmarMediaPlayerScreen(
                    uri = Uri.parse(uri),
                    title = title.ifBlank { if (isVideo) "Video" else "Audio" },
                    isVideo = isVideo,
                    onBack = { finish() },
                )
            }
        }
    }
}

@Composable
private fun AmarMediaPlayerScreen(uri: Uri, title: String, isVideo: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isVideo) Color.Black else Cream)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isVideo) Color.Black else Cream)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Close", color = if (isVideo) Color.White else WarmBrown) }
            Text(
                text = title,
                color = if (isVideo) Color.White else CharcoalSoft,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (isVideo) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = CreamLight,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Amar Audio Player", style = MaterialTheme.typography.titleLarge, color = CharcoalSoft)
                        Spacer(Modifier.height(8.dp))
                        Text(title, style = MaterialTheme.typography.bodyMedium, color = WarmBrown, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(20.dp))
                        AndroidView(
                            factory = { PlayerView(it).apply { this.player = player; useController = true } },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                        )
                    }
                }
            }
        }
    }
}
