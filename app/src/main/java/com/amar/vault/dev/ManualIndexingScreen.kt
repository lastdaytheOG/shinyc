package com.amar.vault.dev

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.DocumentIndexer
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark

@Composable
fun ManualIndexingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val status by DeveloperIndexController.status.collectAsState()
    var force by remember { mutableStateOf(false) }

    fun persist(uris: List<Uri>) = uris.forEach { uri ->
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val pickSingleFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        persist(listOf(uri))
        DeveloperIndexController.indexPickedUris(context, listOf(uri))
    }

    val pickFiles = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        persist(uris)
        DeveloperIndexController.indexPickedUris(context, uris)
    }

    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val children = listTreeChildren(context, treeUri)
        val label = "Index folder: ${treeUri.lastPathSegment ?: "selected"} (${children.size})"
        DeveloperIndexController.indexFolderTree(context, children, label)
    }

    val supportedMimes = remember {
        (listOf("image/*") + DocumentIndexer.SUPPORTED_TYPES).toTypedArray()
    }

    DevScaffold(title = "Index Files", subtitle = "Reuses the production indexing pipeline", onBack = onBack) {

        StatusCard(status)

        DevSectionLabel("Controls")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DevButton("Pause", { DeveloperIndexController.pause() },
                enabled = status.phase == DeveloperIndexController.Phase.RUNNING, modifier = Modifier.weight(1f))
            DevButton("Resume", { DeveloperIndexController.resume() },
                enabled = status.phase == DeveloperIndexController.Phase.PAUSED, modifier = Modifier.weight(1f))
            DevOutlineButton("Cancel", { DeveloperIndexController.cancel() },
                enabled = DeveloperIndexController.isBusy, modifier = Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Checkbox(checked = force, onCheckedChange = { force = it })
            Text(
                "Force re-index (re-runs discovery; unchanged content is still skipped by the pipeline's own content-hash dedup)",
                fontSize = 12.sp, color = WarmBrownDark
            )
        }

        DevSectionLabel("Index images by folder")
        // These scan MediaStore, which shows an app other apps' images but not their documents:
        // "Index Downloads" used to finish with 0 / 0 beside a folder full of PDFs.
        Text(
            "Images only. PDF, DOCX, XLSX and EPUB files are added with the pickers below.",
            fontSize = 11.sp, color = WarmBrown, modifier = Modifier.padding(bottom = 8.dp)
        )
        val busy = DeveloperIndexController.isBusy
        DeveloperIndexController.ImagePreset.values().forEach { preset ->
            DevButton(
                "Index images in ${preset.label}",
                { DeveloperIndexController.indexImagePreset(context, preset, force) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        DevSectionLabel("Pick manually")
        DevButton("Pick a file…", { pickSingleFile.launch(supportedMimes) },
            enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        DevButton("Pick multiple files…", { pickFiles.launch(supportedMimes) },
            enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        DevButton("Pick a folder…", { pickFolder.launch(null) },
            enabled = !busy, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))

        DevSectionLabel("Full")
        DevButton("Full re-index (all configured folders)", { DeveloperIndexController.fullReindex(context) },
            enabled = !busy, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))
        Text(
            "Note: folder picks index supported files directly inside the chosen folder (non-recursive). " +
            "Images route to IndexingPipeline; PDF/DOCX/XLSX/EPUB route to DocumentIndexer.",
            fontSize = 11.sp, color = WarmBrown
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(status: DeveloperIndexController.Status) {
    DevCard {
        Text(
            status.label.ifBlank { "Idle" },
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft
        )
        Spacer(Modifier.height(6.dp))
        DevKeyValue("Phase", status.phase.name)
        DevKeyValue("Progress", "${status.processed} / ${status.total}")
        DevKeyValue("Indexed", status.indexed.toString())
        DevKeyValue("Skipped", status.skipped.toString())
        DevKeyValue("Failed", status.failed.toString())
        DevKeyValue("Duration", "${status.durationMs} ms")
        if (status.note != null) DevKeyValue("Note", status.note)
    }
}

/** List immediate child document URIs of a SAF tree (non-recursive). Uses DocumentsContract to
 *  avoid pulling in androidx.documentfile. Folders are returned too but skipped by the indexer. */
private fun listTreeChildren(context: android.content.Context, treeUri: Uri): List<Uri> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )
    val out = mutableListOf<Uri>()
    runCatching {
        context.contentResolver.query(
            childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0)
                out += DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            }
        }
    }
    return out
}
