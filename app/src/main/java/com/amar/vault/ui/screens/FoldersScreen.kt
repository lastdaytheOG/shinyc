package com.amar.vault.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.amar.vault.BulkScanWorker
import com.amar.vault.DocumentFolders
import com.amar.vault.DocumentIndexer
import com.amar.vault.PhotoFolders
import com.amar.vault.ScanPreferences
import com.amar.vault.VaultDatabase
import com.amar.vault.indexing.DocumentImportQueue
import com.amar.vault.indexing.FileToRead
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import com.amar.vault.ui.components.GroupedRow
import com.amar.vault.ui.components.GroupedSection
import com.amar.vault.ui.components.GroupedToggleRow
import com.amar.vault.ui.components.LargeTitle
import com.amar.vault.ui.components.Tints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PHOTO_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun FoldersScreen(onBack: () -> Unit, backLabel: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current
    BackHandler(onBack = onBack)

    val prefs by ScanPreferences.prefsFlow(context).collectAsState(initial = null)
    var folders by remember { mutableStateOf<List<PhotoFolders.Folder>?>(null) }
    var canSeePhotos by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, PHOTO_PERMISSION) == PackageManager.PERMISSION_GRANTED)
    }
    var turnedOnSomething by remember { mutableStateOf(false) }
    var documentNote by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, canSeePhotos) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            canSeePhotos = ContextCompat.checkSelfPermission(context, PHOTO_PERMISSION) == PackageManager.PERMISSION_GRANTED
            folders = withContext(Dispatchers.IO) { PhotoFolders.list(context) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (turnedOnSomething) BulkScanWorker.enqueueAfterCurrent(context.applicationContext)
        }
    }

    val askForPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        canSeePhotos = granted
    }

    val queue = remember { DocumentImportQueue.get(context) }
    val db = remember { VaultDatabase.get(context) }

    fun addDocuments(find: suspend () -> List<FileToRead>) {
        documentNote = VaultStrings.lookingInFolder(lang)
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val known = db.documentImportDao().getAll().map { it.uri }.toSet()
                val files = find().filterNot { it.uri.toString() in known }
                if (files.isEmpty()) 0 else queue.addPicked(files).size
            }
            documentNote = if (added > 0) VaultStrings.documentsAdded(lang, added) else VaultStrings.noNewDocuments(lang)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
        if (tree == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val app = context.applicationContext
        addDocuments { DocumentFolders.documentsIn(app, tree) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        val app = context.applicationContext
        addDocuments { uris.map { FileToRead(it, app.contentResolver.getType(it).orEmpty()) } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .padding(start = 4.dp, top = 8.dp)
                .clickable(role = Role.Button, onClick = onBack)
                .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Text(backLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }

        LargeTitle(VaultStrings.foldersTitle(lang))

        val current = prefs
        val found = folders.orEmpty()
        val total = found.sumOf { it.count }

        Column(verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)) {
            if (current != null) {
                GroupedSection {
                    GroupedToggleRow(
                        title = VaultStrings.allPhotos(lang),
                        subtitle = VaultStrings.photosOnDevice(lang, total),
                        icon = Icons.Rounded.PhotoLibrary,
                        tint = Tints.Blue,
                        checked = current.scanAll,
                        onCheckedChange = { on ->
                            if (on) turnedOnSomething = true
                            scope.launch { ScanPreferences.update(context) { it.copy(scanAll = on) } }
                        }
                    )
                }

                if (!current.scanAll) {
                    GroupedSection(
                        header = VaultStrings.photoFoldersHeader(lang),
                        footer = VaultStrings.photoFoldersFooter(lang)
                    ) {
                        when {
                            !canSeePhotos -> GroupedRow(
                                title = VaultStrings.allowPhotos(lang),
                                icon = Icons.Rounded.PhotoLibrary,
                                tint = Tints.Blue,
                                titleColor = MaterialTheme.colorScheme.primary,
                                onClick = { askForPhotos.launch(PHOTO_PERMISSION) }
                            )
                            folders != null && found.isEmpty() -> GroupedRow(title = VaultStrings.noPhotoFolders(lang))
                            else -> {
                                val repeated = found.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
                                found.forEach { folder ->
                                    val (icon, tint) = iconFor(folder.path)
                                    GroupedToggleRow(
                                        title = folder.name,
                                        subtitle = if (folder.name.lowercase() in repeated) {
                                            VaultStrings.photosCount(lang, folder.count) + " · " + folder.path.trimEnd('/')
                                        } else VaultStrings.photosCount(lang, folder.count),
                                        icon = icon,
                                        tint = tint,
                                        checked = PhotoFolders.isOn(current, folder.path),
                                        onCheckedChange = { on ->
                                            if (on) turnedOnSomething = true
                                            scope.launch {
                                                ScanPreferences.update(context) {
                                                    if (on) PhotoFolders.turnedOn(it, folder.path) else PhotoFolders.turnedOff(it, folder.path)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            GroupedSection(header = VaultStrings.docsNav(lang), footer = documentNote) {
                GroupedRow(
                    title = VaultStrings.addDocumentFolder(lang),
                    icon = Icons.Rounded.CreateNewFolder,
                    tint = Tints.Orange,
                    onClick = { folderPicker.launch(null) }
                )
                GroupedRow(
                    title = VaultStrings.addDocuments(lang),
                    icon = Icons.AutoMirrored.Rounded.NoteAdd,
                    tint = Tints.Indigo,
                    onClick = { filePicker.launch(DocumentIndexer.SUPPORTED_TYPES.toTypedArray()) }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun iconFor(path: String): Pair<ImageVector, androidx.compose.ui.graphics.Color> = when {
    path.contains("Screenshots", ignoreCase = true) -> Icons.Rounded.Screenshot to Tints.Indigo
    path.contains("WhatsApp", ignoreCase = true) -> Icons.AutoMirrored.Rounded.Chat to Tints.Green
    path.contains("Camera", ignoreCase = true) -> Icons.Rounded.PhotoCamera to Tints.Gray
    path.contains("Download", ignoreCase = true) -> Icons.Rounded.Download to Tints.Blue
    else -> Icons.Rounded.Folder to Tints.Teal
}
