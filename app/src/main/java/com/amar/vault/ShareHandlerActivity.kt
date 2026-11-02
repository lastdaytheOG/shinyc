package com.amar.vault

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.AmarTheme
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import com.amar.vault.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import com.amar.vault.ui.components.save.*
import androidx.core.content.IntentCompat
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class ShareHandlerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("ShareAudit", "ShareHandlerActivity launched")

        val sessionId = UUID.randomUUID().toString()
        val sourcePackage = getReferrerPackageName()
        
        val initialText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)

        setContent {
            AmarTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var showSheet by remember { mutableStateOf(true) }
                
                var session by remember { mutableStateOf<IngestionSession?>(null) }
                var attachments by remember { mutableStateOf<List<IngestionAttachment>?>(null) }

                var isSaving by remember { mutableStateOf(false) }
                var showSuccess by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val (s, a) = withContext(Dispatchers.IO) {
                        prepareIngestionData(intent, sessionId, sourcePackage)
                    }
                    session = s
                    attachments = a
                }

                Surface(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (showSheet) {
                        ModalBottomSheet(
                            onDismissRequest = {
                                showSheet = false
                                finish()
                            },
                            sheetState = sheetState,
                            containerColor = Color.Transparent, // Let the inner sheet handle the color
                            dragHandle = null // Custom handle inside
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(0.9f)
                                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                                    .background(if (isSystemInDarkTheme()) Color(0xFF121110) else Cream)
                            ) {
                                SavePreviewSheet(
                                    isLoading = attachments == null,
                                    attachments = attachments,
                                    initialText = initialText,
                                    isSaving = isSaving,
                                    onCancel = {
                                        showSheet = false
                                        finish()
                                    },
                                    onSave = {
                                        isSaving = true
                                    }
                                )
                            }
                        }
                    }

                    // Handle Save logic after Phase 2 completes if user clicked save early
                    LaunchedEffect(isSaving, attachments) {
                        if (isSaving && attachments != null && session != null) {
                            // Proceed to save
                            showSheet = false
                            showSuccess = true
                        }
                    }
                    
                    SuccessAnimation(
                        visible = showSuccess,
                        onAnimationComplete = {
                            saveAndFinish(session!!, attachments!!)
                        }
                    )
                }
            }
        }
    }

    private fun saveAndFinish(session: IngestionSession, attachments: List<IngestionAttachment>) {
        ShareCaptureManager.capture(applicationContext, session, attachments)
        finish()
    }

    private fun prepareIngestionData(intent: Intent, sessionId: String, sourcePackage: String): Pair<IngestionSession, List<IngestionAttachment>> {
        val action = intent.action ?: ""
        val type = intent.type ?: ""
        
        val session = IngestionSession(
            id = sessionId,
            sourceType = SourceType.ANDROID_SHARE,
            action = action,
            type = type,
            sourcePackage = sourcePackage,
            timestamp = System.currentTimeMillis(),
            status = SessionStatus.CAPTURING
        )

        CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.RECEIVED, "action=$action type=$type source=$sourcePackage")

        val attachments = mutableListOf<IngestionAttachment>()

        // 1. Extract EXTRA_TEXT (fall back to ClipData text when the app used clips only)
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val sharedHtml = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)

        if (!sharedText.isNullOrBlank()) {
            // Prefer the openable URL embedded in the text so links route/dedup correctly.
            val extractedUrl = ShareUrlExtractor.extractFirstUrl(sharedText)
            val openTarget = extractedUrl ?: sharedText
            // Dedup on the normalized URL (tracking params stripped) or the raw text.
            val hashBasis = if (extractedUrl != null) ShareUrlExtractor.normalizeForHash(extractedUrl) else sharedText.trim()
            val hash = hashBasis.sha256()
            CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.HASH_GENERATED, "type=TEXT url=${extractedUrl ?: "-"}")

            val rawExtras = runCatching {
                JSONObject().apply {
                    put("text", sharedText)
                    if (!sharedHtml.isNullOrBlank()) put("html", sharedHtml)
                    if (extractedUrl != null) put("extractedUrl", extractedUrl)
                }.toString()
            }.getOrNull()

            attachments.add(
                IngestionAttachment(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    status = AttachmentStatus.READY,
                    errorCode = AttachmentError.NONE,
                    attachmentType = "TEXT",
                    originalUri = openTarget,
                    localPath = null,
                    mimeType = "text/plain",
                    filename = null,
                    contentHash = hash,
                    width = null, height = null, duration = null, fileSize = sharedText.length.toLong(),
                    pageCount = null, domain = ShareUrlExtractor.extractDomain(sharedText),
                    artist = null, album = null, latitude = null, longitude = null,
                    thumbnailPath = null,
                    previewTitle = subject,
                    rawExtrasJson = rawExtras
                )
            )
        }

        // 2. Extract EXTRA_STREAM(s) — IntentCompat handles the API 33+ typed getters.
        val uris = mutableListOf<Uri>()
        if (Intent.ACTION_SEND == action) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
        }

        // 3. Fallback to ClipData URIs if EXTRA_STREAM is missing but clips exist
        if (uris.isEmpty() && intent.clipData != null) {
            val clipData = intent.clipData!!
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        if (uris.isNotEmpty()) {
            CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.PERMISSION_GRANTED, "streams=${uris.size}")
        }

        for (uri in uris) {
            val mimeType = contentResolver.getType(uri) ?: type.takeIf { it.isNotBlank() } ?: "*/*"
            val filename = resolveFileName(uri)
            val extension = getExtension(mimeType, filename)
            
            val importDir = File(filesDir, "shared_imports")
            if (!importDir.exists()) importDir.mkdirs()
            val destFile = File(importDir, "${UUID.randomUUID()}.$extension")
            
            var copySuccess = false
            var hash: String? = null
            
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                            output.write(buffer, 0, bytesRead)
                        }
                        hash = digest.digest().joinToString("") { "%02x".format(it) }
                        copySuccess = true
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ShareAudit", "Failed to copy URI: $uri", e)
                CaptureTelemetry.failure(
                    sessionId = sessionId,
                    stage = CaptureTelemetry.Stage.COPIED,
                    mime = mimeType,
                    uri = uri.toString(),
                    throwable = e,
                    reason = "Could not read/copy the shared stream (permission revoked or source unreadable)",
                    recovery = "attachment recorded as FAILED(COPY_FAILED); other attachments continue"
                )
            }

            if (copySuccess && hash != null) {
                CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.COPIED, "file=${destFile.name} bytes=${destFile.length()}")
                val metadata = MetadataExtractor.extract(applicationContext, destFile, mimeType)
                CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.METADATA_EXTRACTED, "mime=$mimeType w=${metadata.width} h=${metadata.height}")
                CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.HASH_GENERATED, "type=STREAM")
                attachments.add(
                    IngestionAttachment(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        status = AttachmentStatus.READY,
                        errorCode = AttachmentError.NONE,
                        attachmentType = "STREAM",
                        originalUri = uri.toString(),
                        localPath = destFile.absolutePath,
                        mimeType = mimeType,
                        filename = filename,
                        contentHash = hash,
                        width = metadata.width,
                        height = metadata.height,
                        duration = metadata.duration,
                        fileSize = metadata.fileSize,
                        pageCount = null,
                        domain = null, artist = null, album = null,
                        latitude = metadata.latitude,
                        longitude = metadata.longitude,
                        thumbnailPath = null,
                        previewTitle = filename,
                        rawExtrasJson = null
                    )
                )
            } else {
                attachments.add(
                    IngestionAttachment(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        status = AttachmentStatus.FAILED,
                        errorCode = AttachmentError.COPY_FAILED,
                        attachmentType = "STREAM",
                        originalUri = uri.toString(),
                        localPath = null,
                        mimeType = mimeType,
                        filename = filename,
                        contentHash = null,
                        width = null, height = null, duration = null, fileSize = null,
                        pageCount = null, domain = null, artist = null, album = null,
                        latitude = null, longitude = null, thumbnailPath = null,
                        previewTitle = null, rawExtrasJson = null
                    )
                )
            }
        }

        return Pair(session, attachments)
    }

    private fun getExtension(mimeType: String, fileName: String?): String {
        if (fileName != null) {
            val lastDot = fileName.lastIndexOf('.')
            if (lastDot >= 0 && lastDot < fileName.length - 1) {
                return fileName.substring(lastDot + 1)
            }
        }
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "dat"
    }

    private fun resolveFileName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (colIdx >= 0 && cursor.moveToFirst()) cursor.getString(colIdx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "shared_file"
    }

    private fun getReferrerPackageName(): String {
        return try {
            val ref = referrer
            if (ref != null && "android-app" == ref.scheme) {
                ref.host ?: ""
            } else {
                callingActivity?.packageName ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun String.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(this.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
