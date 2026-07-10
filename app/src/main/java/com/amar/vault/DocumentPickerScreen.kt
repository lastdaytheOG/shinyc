package com.amar.vault

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private val PICKER_MIME_TYPES: Array<String> =
    DocumentIndexer.SUPPORTED_TYPES.toTypedArray()

@Composable
fun DocumentPickerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var results by remember { mutableStateOf<List<IndexResult>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    var totalChunks by rememberSaveable { mutableIntStateOf(0) }

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

        isRunning = true
        results = emptyList()

        scope.launch {
            val indexer = DocumentIndexer.getInstance(context)
            val docs = uris.mapNotNull { uri ->
                val mime = context.contentResolver.getType(uri) ?: return@mapNotNull null
                uri to mime
            }

            val collected = mutableListOf<IndexResult>()
            indexer.indexBatch(docs, concurrency = 2).collectLatest { result ->
                collected.add(result)
                results = collected.toList()
                if (result is IndexResult.Success) {
                    totalChunks += result.chunkCount
                }
            }

            isRunning = false
        }
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose Documents")
        }

        Spacer(Modifier.height(16.dp))

        // Progress
        if (isRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Indexing documents...", style = MaterialTheme.typography.bodySmall)
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
                            is IndexError.UnsupportedFormat -> "Unsupported: ${e.mimeType}"
                            is IndexError.ExtractionFailed -> "Extraction failed"
                            is IndexError.StorageFailed -> "Storage error"
                            IndexError.EmptyContent -> "Document is empty"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}