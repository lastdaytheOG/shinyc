package com.amar.vault.ui.screens

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amar.vault.BuildConfig
import com.amar.vault.ScanPreferences
import com.amar.vault.VaultDatabase
import com.amar.vault.photos.PhotoModelDownloader
import com.amar.vault.photos.PhotoModelFiles
import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onOpenDevTools: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current

    val prefs by ScanPreferences.prefsFlow(context).collectAsState(initial = null)
    val developerMode = prefs?.developerMode == true
    val appLockEnabled = prefs?.appLockEnabled == true
    val largerText = prefs?.largerText == true
    val currentLang = prefs?.language ?: "system"

    val totalCount by remember { VaultDatabase.get(context).vaultDao().observeCount() }.collectAsState(initial = 0)

    val modelFiles = remember { PhotoModelFiles(context) }
    var photoModelReady by remember { mutableStateOf(modelFiles.present()) }
    val downloader = remember { PhotoModelDownloader(context) }
    val downloadState by downloader.state.collectAsState()

    var tapCount by remember { mutableStateOf(0) }
    val showDeveloperSection = BuildConfig.DEBUG || developerMode || tapCount >= 7

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = VaultStrings.settingsNav(lang),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp)
        )

        SettingsSection(title = VaultStrings.sectionSecurity(lang)) {
            SettingsToggleRow(
                icon = { tint ->
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                },
                title = VaultStrings.appLockTitle(lang),
                subtitle = VaultStrings.appLockDesc(lang),
                checked = appLockEnabled,
                onCheckedChange = { enable -> scope.launch { ScanPreferences.setAppLock(context, enable) } }
            )
        }

        Spacer(Modifier.height(20.dp))

        SearchPrivacySection()

        Spacer(Modifier.height(20.dp))

        SettingsSection(title = VaultStrings.sectionSearchModels(lang)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Outlined.PhotoCamera,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = VaultStrings.photoSearchTitle(lang),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = VaultStrings.photoSearchDesc(lang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (photoModelReady || downloadState is PhotoModelDownloader.DownloadState.Completed) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = VaultStrings.ready(lang),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    when (val state = downloadState) {
                        is PhotoModelDownloader.DownloadState.Downloading -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = VaultStrings.downloadingFile(lang, state.currentFile, state.percent),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        is PhotoModelDownloader.DownloadState.Verifying -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = VaultStrings.verifying(lang),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is PhotoModelDownloader.DownloadState.Failed -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = VaultStrings.downloadFailed(lang, state.reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { scope.launch { downloader.download() } },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(VaultStrings.retryDownload(lang))
                            }
                        }
                        is PhotoModelDownloader.DownloadState.Idle -> {
                            if (!photoModelReady) {
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val ok = downloader.download()
                                            if (ok) photoModelReady = true
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(VaultStrings.downloadPhotoModel(lang))
                                }
                            }
                        }
                        is PhotoModelDownloader.DownloadState.Completed -> {
                            photoModelReady = true
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = VaultStrings.meaningModelTitle(lang),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = VaultStrings.meaningModelDesc(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://huggingface.co/lastabhi/amar-bge-m3")
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(VaultStrings.openModelPage(lang))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SettingsSection(title = VaultStrings.sectionDisplay(lang)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsToggleRow(
                        icon = { tint ->
                            Icon(Icons.Outlined.Translate, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        },
                        title = VaultStrings.largerTextTitle(lang),
                        subtitle = VaultStrings.largerTextDesc(lang),
                        checked = largerText,
                        onCheckedChange = { enable ->
                            scope.launch { ScanPreferences.setLargerText(context, enable) }
                        },
                        containerIsFlat = true
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = VaultStrings.languageTitle(lang),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LanguageChoiceChip(
                            label = "English",
                            isSelected = currentLang == "en",
                            onClick = { scope.launch { ScanPreferences.setLanguage(context, "en") } }
                        )
                        LanguageChoiceChip(
                            label = "हिन्दी",
                            isSelected = currentLang == "hi",
                            onClick = { scope.launch { ScanPreferences.setLanguage(context, "hi") } }
                        )
                        LanguageChoiceChip(
                            label = VaultStrings.languageSystem(lang),
                            isSelected = currentLang == "system",
                            onClick = { scope.launch { ScanPreferences.setLanguage(context, "system") } }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        SettingsSection(title = VaultStrings.sectionAbout(lang)) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Amar Vault",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = VaultStrings.offlineDesc(lang),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = VaultStrings.totalItems(lang, totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = VaultStrings.versionLine(lang),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { if (tapCount < 7) tapCount++ }
                    )
                }
            }
        }

        if (showDeveloperSection) {
            Spacer(Modifier.height(20.dp))

            SettingsSection(title = VaultStrings.sectionDeveloper(lang)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = VaultStrings.developerModeTitle(lang),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = VaultStrings.developerModeDesc(lang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Switch(
                                checked = BuildConfig.DEBUG || developerMode,
                                enabled = !BuildConfig.DEBUG,
                                onCheckedChange = { on ->
                                    scope.launch { ScanPreferences.setDeveloperMode(context, on) }
                                }
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onOpenDevTools,
                            enabled = BuildConfig.DEBUG || developerMode,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(VaultStrings.openDevTools(lang))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    containerIsFlat: Boolean = false
) {
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                icon(MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
    if (containerIsFlat) {
        row()
    } else {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) { row() }
        }
    }
}

@Composable
private fun LanguageChoiceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}
