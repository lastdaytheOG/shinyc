package com.amar.vault

import androidx.compose.material.icons.rounded.Screenshot
import com.amar.vault.ui.components.GroupedToggleRow
import com.amar.vault.ui.components.IconBadge
import com.amar.vault.ui.components.Tints
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.automirrored.rounded.Chat
import android.provider.MediaStore
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.amar.vault.ui.LocalAppLanguage
import com.amar.vault.ui.VaultStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(onComplete: () -> Unit, onDocumentsChosen: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = LocalAppLanguage.current

    var photoCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            withContext(Dispatchers.IO) {
                photoCounts = countPhotosPerFolder(context)
            }
        }
    }

    var scanAll by remember { mutableStateOf(false) }
    var scanScreenshots by remember { mutableStateOf(true) }
    var scanCamera by remember { mutableStateOf(false) }
    var scanWhatsApp by remember { mutableStateOf(false) }
    var scanDownloads by remember { mutableStateOf(false) }
    var scanDocuments by remember { mutableStateOf(true) }

    val totalPhotos = if (scanAll) {
        photoCounts["Total"] ?: 0
    } else {
        var count = 0
        if (scanScreenshots) count += (photoCounts["Screenshots"] ?: 0)
        if (scanCamera) count += (photoCounts["Camera"] ?: 0)
        if (scanWhatsApp) count += (photoCounts["WhatsApp"] ?: 0)
        if (scanDownloads) count += (photoCounts["Downloads"] ?: 0)
        count
    }

    val estimatedMinutes = (totalPhotos * 0.5 / 60).toInt().coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(56.dp))

        IconBadge(Icons.Rounded.Description, Tints.Blue, size = 72)

        Spacer(Modifier.height(20.dp))

        Text(
            text = VaultStrings.onboardingTitle(lang),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = VaultStrings.onboardingSubtitle(lang),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = VaultStrings.onboardingQuestion(lang),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = VaultStrings.onboardingChangeNote(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                GroupedToggleRow(
                    title = VaultStrings.allPhotos(lang),
                    subtitle = VaultStrings.photosOnDevice(lang, photoCounts["Total"] ?: 0),
                    icon = Icons.Rounded.PhotoLibrary,
                    tint = Tints.Blue,
                    checked = scanAll,
                    onCheckedChange = { scanAll = it },
                )
                if (!scanAll) {
                    GroupedToggleRow(
                        title = VaultStrings.screenshotsChoice(lang),
                        subtitle = VaultStrings.photosCount(lang, photoCounts["Screenshots"] ?: 0),
                        icon = Icons.Rounded.Screenshot,
                        tint = Tints.Indigo,
                        checked = scanScreenshots,
                        onCheckedChange = { scanScreenshots = it },
                    )
                    GroupedToggleRow(
                        title = VaultStrings.cameraChoice(lang),
                        subtitle = VaultStrings.photosCount(lang, photoCounts["Camera"] ?: 0),
                        icon = Icons.Rounded.PhotoCamera,
                        tint = Tints.Gray,
                        checked = scanCamera,
                        onCheckedChange = { scanCamera = it },
                    )
                    GroupedToggleRow(
                        title = VaultStrings.whatsappChoice(lang),
                        subtitle = VaultStrings.photosCount(lang, photoCounts["WhatsApp"] ?: 0),
                        icon = Icons.AutoMirrored.Rounded.Chat,
                        tint = Tints.Green,
                        checked = scanWhatsApp,
                        onCheckedChange = { scanWhatsApp = it },
                    )
                    GroupedToggleRow(
                        title = VaultStrings.downloadsChoice(lang),
                        subtitle = VaultStrings.photosCount(lang, photoCounts["Downloads"] ?: 0),
                        icon = Icons.Rounded.Download,
                        tint = Tints.Blue,
                        checked = scanDownloads,
                        onCheckedChange = { scanDownloads = it },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            GroupedToggleRow(
                title = VaultStrings.documentsChoice(lang),
                subtitle = VaultStrings.documentsChoiceHint(lang),
                icon = Icons.Rounded.Description,
                tint = Tints.Orange,
                checked = scanDocuments,
                onCheckedChange = { scanDocuments = it },
            )
        }

        Spacer(Modifier.height(24.dp))

        if (totalPhotos > 0) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            VaultStrings.estimateTitle(lang),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            VaultStrings.estimateLine(lang, totalPhotos, estimatedMinutes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            VaultStrings.estimateNote(lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    val prefs = ScanPreferences.Prefs(
                        onboardingDone = true,
                        scanAll = scanAll,
                        scanScreenshots = scanScreenshots,
                        scanCamera = scanCamera,
                        scanWhatsApp = scanWhatsApp,
                        scanDownloads = scanDownloads,
                        scanDocuments = scanDocuments,
                    )
                    if (scanDocuments) onDocumentsChosen()
                    val app = context.applicationContext
                    withContext(NonCancellable) {
                        ScanPreferences.save(app, prefs)
                        ScanPreferences.markOnboardingDone(app)
                        if (totalPhotos > 0) BulkScanWorker.enqueue(app)
                    }

                    onComplete()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = scanAll || scanScreenshots || scanCamera || scanWhatsApp || scanDownloads || scanDocuments,
        ) {
            Text(
                text = if (totalPhotos > 0) VaultStrings.startReading(lang, totalPhotos)
                else VaultStrings.getStarted(lang),
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = {
            scope.launch {
                ScanPreferences.markOnboardingDone(context)
                onComplete()
            }
        }) {
            Text(VaultStrings.skipForNow(lang), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(32.dp))
    }
}

private fun countPhotosPerFolder(context: android.content.Context): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()

    val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    val projection = arrayOf(MediaStore.Images.Media._ID)

    context.contentResolver.query(
        collection, projection, null, null, null
    )?.use { cursor ->
        counts["Total"] = cursor.count
    }

    val folderMap = mapOf(
        "Screenshots" to listOf("Screenshots/", "DCIM/Screenshots/"),
        "Camera"      to listOf("DCIM/Camera/"),
        "WhatsApp"    to listOf(
            "WhatsApp/Media/WhatsApp Images/",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        ),
        "Downloads"   to listOf("Download/", "Downloads/"),
    )

    val pathColumn = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.RELATIVE_PATH
    } else {
        MediaStore.Images.Media.DATA
    }

    folderMap.forEach { (name, paths) ->
        val selection = paths.joinToString(" OR ") { "$pathColumn LIKE ?" }
        val args = paths.map { "%$it%" }.toTypedArray()

        context.contentResolver.query(
            collection, projection, selection, args, null
        )?.use { cursor ->
            counts[name] = cursor.count
        }
    }

    return counts
}
