package com.amar.vault.ui.components

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amar.vault.PictureAddress
import com.amar.vault.VaultLog
import java.io.File

@Composable
fun VaultPicture(
    address: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    crossfade: Boolean = true,
    failedIconSize: Dp = 28.dp,
    failedMessage: String? = null,
) {
    val context = LocalContext.current
    val candidates = remember(address) {
        PictureAddress.candidates(address, Environment.getExternalStorageDirectory().path)
    }
    var tried by remember(address) { mutableIntStateOf(0) }
    var failed by remember(address) { mutableStateOf(candidates.isEmpty()) }

    if (failed) {
        Column(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = if (failedMessage == null) contentDescription else null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(failedIconSize)
            )
            if (failedMessage != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = failedMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }
        return
    }

    val candidate = candidates[tried]
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(if (candidate.startsWith("/")) File(candidate) else candidate)
            .crossfade(crossfade)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        onError = { state ->
            VaultLog.e(
                "VaultPicture",
                "Cannot load picture, route ${tried + 1} of ${candidates.size}: " +
                    "${state.result.throwable.javaClass.simpleName}: ${state.result.throwable.message}"
            )
            if (tried < candidates.lastIndex) tried++ else failed = true
        }
    )
}
