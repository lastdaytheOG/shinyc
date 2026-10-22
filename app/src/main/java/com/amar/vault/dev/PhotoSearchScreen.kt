package com.amar.vault.dev

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.photos.PhotoBackfillWorker
import com.amar.vault.photos.PhotoSearchBenchmark
import com.amar.vault.photos.photoSearchOf
import com.amar.vault.ui.theme.WarmBrownDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Photo search on this device: whether its models are here, how many pictures can be found by
 * what is in them and how many still wait for that, a way to embed them now, and a timing run whose
 * result is meant to be read out (or photographed) from a real phone.
 */
@Composable
fun PhotoSearchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val photos = remember { photoSearchOf(context) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Int?>(null) }
    var stored by remember { mutableStateOf<Int?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            photos.install()
            pending = photos.pending()
            stored = photos.modelId?.let { com.amar.vault.VaultDatabase.get(context).photoVectorDao().countOf(it) }
        }
    }

    DevScaffold(title = "Photo search", subtitle = "Find pictures by what is in them", onBack = onBack) {
        DevSectionLabel("On this device")
        DevCard {
            DevKeyValue("Models", if (photos.problem == null) "here" else "missing")
            photos.problem?.let { Text(it, fontSize = 12.sp, color = WarmBrownDark) }
            DevKeyValue("Model", photos.modelId ?: "—")
            DevKeyValue("Pictures with a vector", stored?.toString() ?: "…")
            DevKeyValue("Pictures still to embed", pending?.toString() ?: "…")
            DevKeyValue("Held for search now", photos.indexed.toString())
        }
        Spacer(Modifier.height(12.dp))
        DevButton(
            text = "Embed pictures now",
            enabled = photos.problem == null,
            onClick = { PhotoBackfillWorker.enqueue(context); refresh++ },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        DevOutlineButton(text = "Refresh counts", onClick = { refresh++ }, modifier = Modifier.fillMaxWidth())

        DevSectionLabel("Timing on this phone")
        DevButton(
            text = if (running) "Timing… (about a minute)" else "Time photo search",
            enabled = photos.problem == null && !running,
            onClick = {
                running = true
                report = null
                scope.launch {
                    report = withContext(Dispatchers.Default) {
                        runCatching { PhotoSearchBenchmark(context, photos).run()?.text ?: "the models did not load" }
                            .getOrElse { "the timing run failed: ${it.message}" }
                    }
                    running = false
                    refresh++
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        report?.let {
            Spacer(Modifier.height(12.dp))
            DevCard { DevMono(it) }
        }
    }
}
