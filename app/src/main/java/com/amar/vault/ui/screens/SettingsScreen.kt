package com.amar.vault.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.BuildConfig
import com.amar.vault.ScanPreferences
import com.amar.vault.VaultDatabase
import com.amar.vault.photos.PhotoModelDownloader
import com.amar.vault.photos.PhotoModelFiles
import com.amar.vault.ui.AppLanguage
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import com.amar.vault.ui.security.AppLockManager
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
            .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
        Text(
            text = VaultStrings.settingsNav(lang),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(20.dp))

        // Security Section
        SectionHeader(title = "SECURITY")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = VaultStrings.appLockTitle(lang),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = VaultStrings.appLockDesc(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = appLockEnabled,
                    onCheckedChange = { enable ->
                        scope.launch {
                            ScanPreferences.setAppLock(context, enable)
                        }
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        SearchPrivacySection()

        Spacer(Modifier.height(24.dp))

        // Photo Search Section (ADR-0002 dynamic download)
        SectionHeader(title = "PHOTO SEARCH")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                            text = VaultStrings.photoSearchTitle(lang),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = VaultStrings.photoSearchDesc(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (photoModelReady || downloadState is PhotoModelDownloader.DownloadState.Completed) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "Ready",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                when (val state = downloadState) {
                    is PhotoModelDownloader.DownloadState.Downloading -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Downloading ${state.currentFile} · ${state.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
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
                            text = "Verifying checksums and activating model...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is PhotoModelDownloader.DownloadState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Download failed: ${state.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(6.dp))
                        Button(
                            onClick = { scope.launch { downloader.download() } },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Retry Download")
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
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Download Photo Search Model (~207 MB)")
                            }
                        }
                    }
                    is PhotoModelDownloader.DownloadState.Completed -> {
                        photoModelReady = true
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionHeader(title = "MEANING SEARCH MODEL")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Meaning search model (~585 MB)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "Finds pages by meaning, in English and Hindi. Download page on Hugging Face.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://huggingface.co/lastabhi/amar-bge-m3")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open model download page")
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Display & Accessibility Section
        SectionHeader(title = "DISPLAY & LANGUAGE")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Larger Text Switch (Simpler view for parents)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = VaultStrings.largerTextTitle(lang),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = VaultStrings.largerTextDesc(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = largerText,
                        onCheckedChange = { enable ->
                            scope.launch {
                                ScanPreferences.setLargerText(context, enable)
                            }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                // Language Selector
                Text(
                    text = VaultStrings.languageTitle(lang),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))

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
                        label = "System",
                        isSelected = currentLang == "system",
                        onClick = { scope.launch { ScanPreferences.setLanguage(context, "system") } }
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // About & Offline Privacy Section
        SectionHeader(title = "ABOUT & PRIVACY")

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Amar Vault",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
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
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "v1.0 (Slim) · Local OCR & Vector Search",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.clickable {
                        if (tapCount < 7) tapCount++
                    }
                )
            }
        }

        // Developer Section
        if (showDeveloperSection) {
            Spacer(Modifier.height(24.dp))
            SectionHeader(title = "DEVELOPER")

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                                text = "Developer Mode",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Unlocks internal dev tools & benchmarking",
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
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open Developer Tools")
                    }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun LanguageChoiceChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}
