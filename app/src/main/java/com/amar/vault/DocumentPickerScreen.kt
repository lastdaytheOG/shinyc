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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amar.vault.indexing.DocumentImport
import com.amar.vault.indexing.DocumentImportQueue
import com.amar.vault.indexing.DocumentRelink
import com.amar.vault.indexing.FileToRead
import com.amar.vault.indexing.ImportFailure
import com.amar.vault.indexing.ImportState
import com.amar.vault.indexing.ImportWords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PICKER_MIME_TYPES: Array<String> =
    DocumentIndexer.SUPPORTED_TYPES.toTypedArray()

/** Keeps the right to read [uris] after the app is closed; a picked file is read from where it sits. */
private fun keepReadable(context: Context, uris: List<Uri>) {
    uris.forEach { uri ->
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * Where documents are added, and where what became of each one is said.
 *
 * The list is the vault's own record ([DocumentImport]), not this screen's memory: it is
 * still there after the app was closed, a file that could not be read says why and what to
 * do, and one that is half-read says how far it is.
 */
@Composable
fun DocumentPickerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { VaultDatabase.get(context) }
    val queue = remember { DocumentImportQueue.get(context) }
    val relink = remember { DocumentRelink.get(context) }
    val imports by remember { db.documentImportDao().observeAll() }.collectAsState(initial = emptyList())
    val reading = imports.count { !it.isFinished }

    // Documents whose file is no longer where it was added from, and what the last search for
    // them found. Looked for again whenever a reading ends: a file that just failed as "gone"
    // may be one of them.
    var missing by remember { mutableStateOf<List<VaultDocument>>(emptyList()) }
    var findResult by remember { mutableStateOf<String?>(null) }
    var finding by remember { mutableStateOf(false) }
    LaunchedEffect(imports.count { it.isFinished }) {
        missing = withContext(Dispatchers.IO) { relink.missing() }
    }
    // The card about missing files is found a moment after the list is drawn and goes in above
    // it; without this the list stayed where it was and showed the card's lower half.
    val listState = rememberLazyListState()
    LaunchedEffect(missing.isNotEmpty()) {
        if (missing.isNotEmpty()) listState.scrollToItem(0)
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        keepReadable(context, uris)
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            // A provider that reports no type is not a reason to drop the file: the
            // indexer also knows a document by its name.
            queue.addPicked(uris.map { uri -> FileToRead(uri, app.contentResolver.getType(uri).orEmpty()) })
        }
    }

    val finder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        finding = true
        scope.launch {
            val looked = missing
            val found = withContext(Dispatchers.IO) { relink.relinkAll(looked, uris) }
            missing = withContext(Dispatchers.IO) { relink.missing() }
            findResult = MissingWords.afterLooking(found = found.size, lookedFor = looked.size, picked = uris.size)
            finding = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // Header
        Text("Import Documents", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "PDF, Word (.docx), Excel (.xlsx) and EPUB. A file stays where it is on your phone; the app keeps its text.",
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
        if (reading > 0) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                (if (reading == 1) "Reading 1 document." else "Reading $reading documents.") +
                    " It carries on if you leave this screen or close the app.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))
        }

        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (missing.isNotEmpty() || findResult != null) {
                item(key = "missing") {
                    MissingFilesCard(
                        missing = missing,
                        result = findResult,
                        finding = finding,
                        onFind = { finder.launch(PICKER_MIME_TYPES) },
                    )
                }
            }

            if (imports.isNotEmpty()) {
                item(key = "heading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Added", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        if (imports.any { it.isFinished }) {
                            TextButton(onClick = { scope.launch(Dispatchers.IO) { db.documentImportDao().clearFinished() } }) {
                                Text("Clear list")
                            }
                        }
                    }
                }
            }

            items(imports, key = { it.id }) { import ->
                ImportCard(import, onTryAgain = { scope.launch(Dispatchers.IO) { queue.retry(import.id) } })
            }
        }
    }
}

/** The words about documents whose file has gone. */
internal object MissingWords {

    fun headline(count: Int): String =
        if (count == 1) "1 document cannot be opened" else "$count documents cannot be opened"

    const val EXPLANATION =
        "The file was moved, renamed or deleted after you added it. Its text can still be searched. " +
            "Show the app where the file is now and it opens again."

    fun afterLooking(found: Int, lookedFor: Int, picked: Int): String = when {
        found == 0 && picked == 1 -> "That is not one of the missing files: its contents are different."
        found == 0 -> "None of those is one of the missing files: their contents are different."
        found == lookedFor -> if (found == 1) "Found it. It opens again." else "Found all $found. They open again."
        else -> "Found $found of $lookedFor."
    }
}

@Composable
private fun MissingFilesCard(missing: List<VaultDocument>, result: String?, finding: Boolean, onFind: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(12.dp)) {
            if (missing.isNotEmpty()) {
                Text(MissingWords.headline(missing.size), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    missing.take(3).joinToString(", ") { it.name } + if (missing.size > 3) " and ${missing.size - 3} more" else "",
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(MissingWords.EXPLANATION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (result != null) {
                if (missing.isNotEmpty()) Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            if (missing.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                if (finding) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Checking the files you chose…", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    OutlinedButton(onClick = onFind, shape = RoundedCornerShape(12.dp)) {
                        Text(if (missing.size == 1) "Find the file" else "Find the files")
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportCard(import: DocumentImport, onTryAgain: () -> Unit) {
    val failure = import.failure ?: ImportFailure.OTHER
    // A file with no words in it is in the vault under its name: a caution, not a failure.
    val noWords = import.state == ImportState.FAILED && failure == ImportFailure.NO_TEXT
    val containerColor = when {
        import.state == ImportState.DONE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        import.state == ImportState.ALREADY_THERE || noWords -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
        import.state == ImportState.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                import.state == ImportState.DONE ->
                    Icon(Icons.Default.CheckCircle, "Read", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                import.state == ImportState.ALREADY_THERE || noWords ->
                    Icon(Icons.Default.Warning, "Note", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.tertiary)
                import.state == ImportState.FAILED ->
                    Icon(Icons.Default.Close, "Not read", Modifier.size(28.dp), tint = MaterialTheme.colorScheme.error)
                else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    import.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ImportWords.progress(import),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (import.state == ImportState.FAILED) {
                    Text(
                        ImportWords.whatToDo(failure, import.origin),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (ImportWords.canTryAgain(failure)) {
                        TextButton(onClick = onTryAgain, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Try again")
                        }
                    }
                }
            }
        }
    }
}
