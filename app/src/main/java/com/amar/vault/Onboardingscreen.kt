package com.amar.vault

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [onDocumentsChosen] is called, before the choices are saved, when the Documents switch is on:
 * documents cannot be found automatically the way photos are, so the caller opens the import
 * screen once onboarding is done.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit, onDocumentsChosen: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load photo counts off the main thread to avoid frame skipping. Counted again each time
    // the screen resumes: on a first launch the first count runs behind the system's photo
    // permission dialog, before access is granted, and reads zero — which left "0 photos" on
    // screen and the scan below never started.
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

    // Rough estimate: ~0.5s per photo with aggressive OCR
    val estimatedMinutes = (totalPhotos * 0.5 / 60).toInt().coerceAtLeast(1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))

        // Logo
        Text("📱", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))

        Text(
            "Welcome to\nAmar Vault",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.5).sp,
            lineHeight = 38.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your private, on-device AI vault.\nNothing leaves your phone.",
            fontSize = 15.sp,
            color = WarmBrown,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(36.dp))

        // Section header
        Text(
            "What should we index?",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = CharcoalSoft,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "You can change this anytime in Settings",
            fontSize = 13.sp,
            color = WarmBrown,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        // Toggle cards
        ToggleCard(
            emoji = "🌐",
            title = "All Photos",
            subtitle = "${photoCounts["Total"] ?: "..."} photos on device",
            checked = scanAll,
            onCheckedChange = { scanAll = it },
        )

        if (!scanAll) {
            Spacer(Modifier.height(10.dp))
            ToggleCard(
                emoji = "📸",
                title = "Screenshots",
                subtitle = "${photoCounts["Screenshots"] ?: "..."} photos",
                checked = scanScreenshots,
                onCheckedChange = { scanScreenshots = it },
            )
            Spacer(Modifier.height(10.dp))
            ToggleCard(
                emoji = "📷",
                title = "Camera",
                subtitle = "${photoCounts["Camera"] ?: "..."} photos",
                checked = scanCamera,
                onCheckedChange = { scanCamera = it },
            )
            Spacer(Modifier.height(10.dp))
            ToggleCard(
                emoji = "💬",
                title = "WhatsApp Images",
                subtitle = "${photoCounts["WhatsApp"] ?: "..."} photos",
                checked = scanWhatsApp,
                onCheckedChange = { scanWhatsApp = it },
            )
            Spacer(Modifier.height(10.dp))
            ToggleCard(
                emoji = "📥",
                title = "Downloads",
                subtitle = "${photoCounts["Downloads"] ?: "..."} photos",
                checked = scanDownloads,
                onCheckedChange = { scanDownloads = it },
            )
        }

        Spacer(Modifier.height(10.dp))
        ToggleCard(
            emoji = "📄",
            title = "Documents",
            subtitle = "PDFs, DOCX, XLSX, EPUB — you pick them next",
            checked = scanDocuments,
            onCheckedChange = { scanDocuments = it },
        )

        Spacer(Modifier.height(28.dp))

        // Estimate
        if (totalPhotos > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CreamLight, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Column {
                    Text(
                        "⏱ Estimated scan time",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WarmBrownDark,
                    )
                    Text(
                        "$totalPhotos photos · ~$estimatedMinutes min",
                        fontSize = 14.sp,
                        color = WarmBrown,
                    )
                    Text(
                        "Runs in background — you can use other apps",
                        fontSize = 12.sp,
                        color = WarmBrown,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // Start button
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
                    ScanPreferences.save(context, prefs)
                    ScanPreferences.markOnboardingDone(context)

                    // Enqueue background scan
                    if (totalPhotos > 0) {
                        BulkScanWorker.enqueue(context)
                    }

                    onComplete()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CharcoalSoft,
                contentColor = Color.White,
            ),
            enabled = scanAll || scanScreenshots || scanCamera || scanWhatsApp || scanDownloads || scanDocuments,
        ) {
            Text(
                if (totalPhotos > 0) "Start Scanning ($totalPhotos photos)"
                else "Get Started",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Skip option
        androidx.compose.material3.TextButton(onClick = {
            scope.launch {
                ScanPreferences.markOnboardingDone(context)
                onComplete()
            }
        }) {
            Text("Skip for now", fontSize = 14.sp, color = WarmBrown)
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ToggleCard(
    emoji: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CreamLight, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft)
                Text(subtitle, fontSize = 12.sp, color = WarmBrown)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = CharcoalSoft,
                    uncheckedTrackColor = CreamLight,
                ),
            )
        }
    }
}

/**
 * Counts photos in known folders by querying MediaStore.
 * Uses cursor.count instead of COUNT(*) projection — which is invalid on Android.
 * Must be called from a background thread (IO dispatcher).
 */
private fun countPhotosPerFolder(context: android.content.Context): Map<String, Int> {
    val counts = mutableMapOf<String, Int>()

    val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    // Use only _id for projection — lightweight, just need row count
    val projection = arrayOf(MediaStore.Images.Media._ID)

    // Total count — no selection filter
    context.contentResolver.query(
        collection, projection, null, null, null
    )?.use { cursor ->
        counts["Total"] = cursor.count
    }

    // Per-folder counts using RELATIVE_PATH (Android 10+) or DATA (older)
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