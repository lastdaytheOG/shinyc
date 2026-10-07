package com.amar.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val PICKER_MIME_TYPES: Array<String> =
    DocumentIndexer.SUPPORTED_TYPES.toTypedArray()

/**
 * The import this screen started. It belongs to the process, not to the screen: a long PDF
 * takes minutes, and an import owned by the screen was cancelled part-way, with nothing said,
 * the moment the user went back to search for what they had just added.
 */
private object DocumentImport {
    data class State(val running: Boolean = false, val results: List<IndexResult> = emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(context: Context, uris: List<Uri>) {
        if (_state.value.running) return
        val app = context.applicationContext
        _state.value = State(running = true)
        scope.launch {
            try {
                // A provider that reports no type is not a reason to drop the file: the
                // indexer also knows a document by its name.
                val documents = uris.map { uri -> uri to app.contentResolver.getType(uri).orEmpty() }
                DocumentIndexer.getInstance(app).indexBatch(documents, concurrency = 2).collect { result ->
                    _state.update { it.copy(results = it.results + result) }
                }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }
}

@Composable
fun DocumentPickerScreen() {
    val context = LocalContext.current
    val import by DocumentImport.state.collectAsState()
    val results = import.results
    val totalChunks = results.sumOf { (it as? IndexResult.Success)?.chunkCount ?: 0 }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult

        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }

        DocumentImport.start(context, uris)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // Header
        Text("Import Documents", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (totalChunks > 0) "$totalChunks chunks indexed total • PDF, DOCX, XLSX, EPUB"
            else "Supported: PDF, DOCX, XLSX, EPUB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        // Import button
        Button(
            onClick = { picker.launch(PICKER_MIME_TYPES) },
            enabled = !import.running,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose Documents")
        }

        Spacer(Modifier.height(16.dp))

        // Progress
        if (import.running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "Indexing documents… it carries on if you leave this screen.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
        }

        // Results
        if (results.isNotEmpty()) {
            Text("Results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results.size) { index ->
                    ResultCard(results[index])
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: IndexResult) {
    val containerColor = when (result) {
        is IndexResult.Success -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        is IndexResult.Duplicate -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        is IndexResult.Failure -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Icon
            when (result) {
                is IndexResult.Success -> Icon(Icons.Default.CheckCircle, "Success", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                is IndexResult.Duplicate -> Icon(Icons.Default.Warning, "Duplicate", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.tertiary)
                is IndexResult.Failure -> Icon(Icons.Default.Close, "Failed", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    when (result) {
                        is IndexResult.Success -> result.fileName
                        is IndexResult.Duplicate -> result.fileName
                        is IndexResult.Failure -> result.fileName
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    when (result) {
                        is IndexResult.Success -> "${result.chunkCount} chunks in ${result.durationMs}ms"
                        is IndexResult.Duplicate -> "Already indexed — skipped"
                        is IndexResult.Failure -> when (val e = result.error) {
                            is IndexError.UnsupportedFormat -> "Unsupported: ${e.mimeType.ifBlank { "unknown file type" }}"
                            is IndexError.ExtractionFailed -> "Extraction failed"
                            is IndexError.StorageFailed -> "Storage error"
                            IndexError.EmptyContent -> "No text could be read from it"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
