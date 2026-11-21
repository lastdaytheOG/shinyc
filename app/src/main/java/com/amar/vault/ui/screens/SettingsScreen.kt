package com.amar.vault.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.amar.vault.BuildConfig
import com.amar.vault.BulkScanWorker
import com.amar.vault.PhotoFolders
import com.amar.vault.ScanPreferences
import com.amar.vault.VaultDatabase
import com.amar.vault.indexing.ppocr.LineModelDownloader
import com.amar.vault.indexing.ppocr.LineModelFiles
import com.amar.vault.photos.PhotoModelDownloader
import com.amar.vault.photos.PhotoModelFiles
import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import com.amar.vault.ui.components.GroupedRow
import com.amar.vault.ui.components.GroupedSection
import com.amar.vault.ui.components.GroupedToggleRow
import com.amar.vault.ui.components.LargeTitle
import com.amar.vault.ui.components.Tints
import com.amar.vault.vectors.Sensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAPS_FOR_DEVELOPER = 7

@Composable
fun SettingsScreen(
    onOpenDevTools: () -> Unit = {}
) {
    var showFolders by rememberSaveable { mutableStateOf(false) }
    val lang = LocalAppLanguage.current
    if (showFolders) {
        FoldersScreen(onBack = { showFolders = false }, backLabel = VaultStrings.settingsNav(lang))
    } else {
        SettingsList(onOpenFolders = { showFolders = true }, onOpenDevTools = onOpenDevTools)
    }
}

@Composable
private fun SettingsList(onOpenFolders: () -> Unit, onOpenDevTools: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current
    val db = remember { VaultDatabase.get(context) }

    val prefs by ScanPreferences.prefsFlow(context).collectAsState(initial = null)
    val developerMode = prefs?.developerMode == true
    val totalCount by remember { db.vaultDao().observeCount() }.collectAsState(initial = 0)

    var folderSummary by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prefs) {
        val current = prefs ?: return@LaunchedEffect
        folderSummary = if (current.scanAll) VaultStrings.allPhotos(lang) else {
            val chosen = withContext(Dispatchers.IO) { PhotoFolders.chosenCount(current, PhotoFolders.list(context)) }
            VaultStrings.foldersChosen(lang, chosen)
        }
    }

    var excludeCodes by remember { mutableStateOf(Sensitive.excludeFromIndex) }
    var learned by remember { mutableIntStateOf(0) }
    var learnedRefresh by remember { mutableIntStateOf(0) }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(learnedRefresh) {
        learned = withContext(Dispatchers.IO) { db.aliasDao().all().size + db.openDao().count() }
    }

    var taps by remember { mutableIntStateOf(0) }
    val showDeveloper = BuildConfig.DEBUG || developerMode

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        LargeTitle(VaultStrings.settingsNav(lang))

        Column(verticalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 8.dp)) {
            GroupedSection(header = VaultStrings.sectionReading(lang)) {
                GroupedRow(
                    title = VaultStrings.foldersTitle(lang),
                    icon = Icons.Rounded.Folder,
                    tint = Tints.Blue,
                    value = folderSummary,
                    onClick = onOpenFolders
                )
                ReadPhotosRows(lang)
            }

            GroupedSection(header = VaultStrings.sectionSearchModels(lang)) {
                PhotoSearchRow(lang)
                TextReaderRow(lang)
            }

            GroupedSection(header = VaultStrings.sectionPrivacy(lang)) {
                GroupedToggleRow(
                    title = VaultStrings.appLockTitle(lang),
                    icon = Icons.Rounded.Lock,
                    tint = Tints.Indigo,
                    checked = prefs?.appLockEnabled == true,
                    onCheckedChange = { on -> scope.launch { ScanPreferences.setAppLock(context, on) } }
                )
                GroupedToggleRow(
                    title = VaultStrings.hideCodesTitle(lang),
                    icon = Icons.Rounded.Password,
                    tint = Tints.Gray,
                    checked = excludeCodes,
                    onCheckedChange = { on ->
                        excludeCodes = on
                        Sensitive.setExcludeFromIndex(context, on)
                    }
                )
                GroupedRow(
                    title = VaultStrings.forgetLearnedTitle(lang),
                    icon = Icons.Rounded.History,
                    tint = Tints.Red,
                    value = learned.takeIf { it > 0 }?.toString(),
                    enabled = learned > 0,
                    onClick = { confirmForget = true },
                    chevron = false
                )
            }

            GroupedSection(header = VaultStrings.sectionDisplay(lang)) {
                LanguageRow(lang = lang, current = prefs?.language ?: "system") { code ->
                    scope.launch { ScanPreferences.setLanguage(context, code) }
                }
                GroupedToggleRow(
                    title = VaultStrings.largerTextTitle(lang),
                    icon = Icons.Rounded.FormatSize,
                    tint = Tints.Blue,
                    checked = prefs?.largerText == true,
                    onCheckedChange = { on -> scope.launch { ScanPreferences.setLargerText(context, on) } }
                )
            }

            if (showDeveloper) {
                GroupedSection(header = VaultStrings.sectionDeveloper(lang)) {
                    GroupedRow(
                        title = VaultStrings.devToolsTitle(lang),
                        icon = Icons.Rounded.Build,
                        tint = Tints.Gray,
                        onClick = onOpenDevTools
                    )
                    if (!BuildConfig.DEBUG) {
                        GroupedRow(
                            title = VaultStrings.hideDevTools(lang),
                            icon = Icons.Rounded.VisibilityOff,
                            tint = Tints.Gray,
                            chevron = false,
                            onClick = {
                                taps = 0
                                scope.launch { ScanPreferences.setDeveloperMode(context, false) }
                            }
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        enabled = !showDeveloper,
                        role = Role.Button,
                        onClick = {
                            taps++
                            if (taps >= TAPS_FOR_DEVELOPER) {
                                scope.launch { ScanPreferences.setDeveloperMode(context, true) }
                                Toast.makeText(context, VaultStrings.devUnlocked(lang), Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    .padding(vertical = 12.dp)
            ) {
                Text(
                    text = VaultStrings.versionLine(lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = VaultStrings.totalItems(lang, totalCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(VaultStrings.forgetLearnedTitle(lang)) },
            text = { Text(VaultStrings.forgetLearnedBody(lang)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            db.aliasDao().clear()
                            db.openDao().clear()
                        }
                        learnedRefresh++
                    }
                }) { Text(VaultStrings.forget(lang), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(VaultStrings.cancel(lang)) }
            }
        )
    }
}

@Composable
private fun PhotoSearchRow(lang: AppLanguage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { PhotoModelFiles(context) }
    var ready by remember { mutableStateOf(files.present()) }
    val downloader = remember { PhotoModelDownloader(context) }
    val state by downloader.state.collectAsState()

    val progress = (state as? PhotoModelDownloader.DownloadState.Downloading)?.percent
    val failed = state is PhotoModelDownloader.DownloadState.Failed
    LaunchedEffect(state) { if (state is PhotoModelDownloader.DownloadState.Completed) ready = true }

    GroupedRow(
        title = VaultStrings.photoSearchTitle(lang),
        subtitle = VaultStrings.photoSearchShort(lang),
        icon = Icons.Rounded.PhotoLibrary,
        tint = Tints.Purple,
        chevron = false,
        trailing = {
            DownloadState(
                lang = lang,
                ready = ready,
                percent = progress ?: if (state is PhotoModelDownloader.DownloadState.Verifying) 100 else null,
                failed = failed,
                onDownload = { scope.launch { if (downloader.download()) ready = true } }
            )
        }
    )
}

@Composable
private fun TextReaderRow(lang: AppLanguage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { LineModelFiles(context) }
    var ready by remember { mutableStateOf(files.present()) }
    var percent by remember { mutableStateOf<Int?>(null) }
    var failed by remember { mutableStateOf(false) }

    GroupedRow(
        title = VaultStrings.linePackTitle(lang),
        subtitle = VaultStrings.linePackShort(lang),
        icon = Icons.Rounded.DocumentScanner,
        tint = Tints.Teal,
        chevron = false,
        trailing = {
            DownloadState(
                lang = lang,
                ready = ready,
                percent = percent,
                failed = failed,
                onDownload = {
                    failed = false
                    percent = 0
                    scope.launch {
                        val ok = LineModelDownloader(files, LineModelDownloader.DEFAULT_BASE_URL).download { done, total ->
                            if (total > 0) percent = (done * 100 / total).toInt()
                        }
                        ready = ok && files.present()
                        failed = !ready
                        percent = null
                    }
                }
            )
        }
    )
}

@Composable
private fun DownloadState(lang: AppLanguage, ready: Boolean, percent: Int?, failed: Boolean, onDownload: () -> Unit) {
    when {
        ready -> Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = VaultStrings.ready(lang),
            tint = Tints.Green,
            modifier = Modifier.size(22.dp)
        )
        percent != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                progress = { percent / 100f },
                strokeWidth = 2.5.dp,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text("$percent%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            modifier = Modifier.clickable(role = Role.Button, onClick = onDownload)
        ) {
            Text(
                text = if (failed) VaultStrings.retryShort(lang) else VaultStrings.downloadShort(lang),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun LanguageRow(lang: AppLanguage, current: String, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val choices = listOf("system" to VaultStrings.languageSystem(lang), "en" to "English", "hi" to "हिन्दी")
    Box {
        GroupedRow(
            title = VaultStrings.languageTitle(lang),
            icon = Icons.Rounded.Language,
            tint = Tints.Blue,
            value = choices.firstOrNull { it.first == current }?.second ?: choices.first().second,
            onClick = { open = true }
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            choices.forEach { (code, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (code == current) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = if (code == current) {
                        { Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
                    } else null,
                    onClick = {
                        open = false
                        onChoose(code)
                    }
                )
            }
        }
    }
}

@Composable
private fun ReadPhotosRows(lang: AppLanguage) {
    val context = LocalContext.current
    val app = context.applicationContext
    val progress by remember { BulkScanWorker.progress(app) }.collectAsState(initial = null)
    var confirmAgain by remember { mutableStateOf(false) }
    val current = progress
    val busy = current != null

    GroupedRow(
        title = VaultStrings.readNewNow(lang),
        subtitle = current?.let {
            if (it.total > 0) VaultStrings.readingProgress(lang, it.current, it.total) else VaultStrings.readingStarting(lang)
        },
        icon = Icons.Rounded.Refresh,
        tint = Tints.Green,
        chevron = false,
        onClick = if (busy) null else ({ BulkScanWorker.enqueueAfterCurrent(app) }),
        trailing = if (busy) ({
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }) else null
    )
    GroupedRow(
        title = VaultStrings.readAllAgain(lang),
        icon = Icons.Rounded.RestartAlt,
        tint = Tints.Orange,
        enabled = !busy,
        chevron = false,
        onClick = { confirmAgain = true }
    )

    if (confirmAgain) {
        AlertDialog(
            onDismissRequest = { confirmAgain = false },
            title = { Text(VaultStrings.readAllAgain(lang)) },
            text = { Text(VaultStrings.readAllAgainBody(lang)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAgain = false
                    BulkScanWorker.enqueueAfterCurrent(app, again = true)
                }) { Text(VaultStrings.readAgainConfirm(lang), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAgain = false }) { Text(VaultStrings.cancel(lang)) }
            }
        )
    }
}
