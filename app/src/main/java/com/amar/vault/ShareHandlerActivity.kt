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
import com.amar.vault.ui.components.saved.CreateFolderSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.core.content.IntentCompat
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class ShareHandlerActivity : ComponentActivity() {

    // Seed folders shown to first-time users before any real folders exist. Merged
    // with the user's real DB categories + created folders at display time.
    private val seedCategories = listOf(
        "Ideas", "Recipes", "Shopping", "Trips", "Learning",
        "Coding", "AI", "Photography", "Books", "Fitness"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("ShareAudit", "ShareHandlerActivity launched")

        val sessionId = UUID.randomUUID().toString()
        val sourcePackage = getReferrerPackageName()
        
        val initialText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
        // If the user picked an Amar Vault Direct Share target, preselect that folder.
        val preselectFolder = intent.getStringExtra(com.amar.vault.share.ShareTargetPublisher.EXTRA_PRESELECT_FOLDER)
            ?.takeIf { it.isNotBlank() }

        setContent {
            AmarTheme {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                var showSheet by remember { mutableStateOf(true) }
                
                val scope = rememberCoroutineScope()
                var session by remember { mutableStateOf<IngestionSession?>(null) }
                var attachments by remember { mutableStateOf<List<IngestionAttachment>?>(null) }

                var selectedCategory by remember { mutableStateOf(preselectFolder ?: "Ideas") }
                var note by remember { mutableStateOf("") }

                var isSaving by remember { mutableStateOf(false) }
                var showSuccess by remember { mutableStateOf(false) }
                var showCreateFolder by remember { mutableStateOf(false) }

                // Real folder state: the user's saved-prefs (folder identity + recency)
                // and the distinct categories already in the DB.
                val prefs by SavedPreferences.prefsFlow(applicationContext)
                    .collectAsState(initial = SavedPreferences.Prefs())
                var dbCategories by remember { mutableStateOf<List<String>>(emptyList()) }
                val locallyCreated = remember { mutableStateListOf<String>() }

                LaunchedEffect(Unit) {
                    val (s, a) = withContext(Dispatchers.IO) {
                        prepareIngestionData(intent, sessionId, sourcePackage)
                    }
                    session = s
                    attachments = a
                    dbCategories = withContext(Dispatchers.IO) {
                        runCatching { VaultDatabase.get(applicationContext).stashItemDao().getDistinctCategories() }
                            .getOrDefault(emptyList())
                    }
                }

                // Recently saved-to folders first, then everything the user actually has,
                // then the seed suggestions — deduped, blanks removed.
                val displayCategories = remember(dbCategories, prefs.knownFolders, prefs.recentlySavedTo, locallyCreated.toList()) {
                    val known = (dbCategories + prefs.knownFolders + locallyCreated + seedCategories)
                        .map { it.trim() }.filter { it.isNotBlank() }.distinct()
                    val recent = prefs.recentlySavedTo.filter { it in known }
                    recent + known.filter { it !in recent }
                }

                // Real per-folder identity (icon + accent) from Phase B folder themes,
                // falling back to the name-derived defaults.
                val emojiFor: (String) -> String = { name ->
                    com.amar.vault.ui.theme.FolderVisuals.styleFor(name, prefs.folderMeta[name]).icon
                }
                val colorFor: (String) -> Color = { name ->
                    com.amar.vault.ui.theme.FolderVisuals.styleFor(name, prefs.folderMeta[name]).accent
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
                                    categories = displayCategories,
                                    selectedCategory = selectedCategory,
                                    onCategorySelected = { selectedCategory = it },
                                    note = note,
                                    onNoteChange = { note = it },
                                    isSaving = isSaving,
                                    getEmojiForCategory = emojiFor,
                                    getColorForCategory = colorFor,
                                    onCreateFolder = { showCreateFolder = true },
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

                    // Inline folder creation, reusing the Phase B create sheet. Persists
                    // the chosen theme/identity to DataStore and selects it immediately.
                    if (showCreateFolder) {
                        CreateFolderSheet(
                            existingNames = displayCategories,
                            onDismiss = { showCreateFolder = false },
                            onCreate = { name, meta ->
                                scope.launch(Dispatchers.IO) {
                                    SavedPreferences.saveFolderMeta(applicationContext, name, meta)
                                }
                                if (name !in locallyCreated) locallyCreated.add(name)
                                selectedCategory = name
                                showCreateFolder = false
                            }
                        )
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
                            saveAndFinish(session!!, attachments!!, selectedCategory, note)
                        }
                    )
                }
            }
        }
    }

    private fun saveAndFinish(session: IngestionSession, attachments: List<IngestionAttachment>, category: String, userNote: String?) {
        ShareCaptureManager.capture(applicationContext, session, attachments, category, userNote)
        // Remember this destination so it surfaces first next time (fire-and-forget;
        // non-critical ordering hint, DataStore write outlives this finishing activity).
        CoroutineScope(Dispatchers.IO).launch {
            SavedPreferences.recordSavedTo(applicationContext, category)
        }
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

// ─── Composable ───

@Composable
fun CategorySelectionSheet(
    categories: List<String>,
    onCategorySelected: (String, String?) -> Unit,
    onSkip: (String?) -> Unit
) {
    val context = LocalContext.current
    val db = remember { VaultDatabase.get(context) }
    
    val darkTheme = isSystemInDarkTheme()
    val screenBg = if (darkTheme) Color(0xFF121110) else Cream
    val cardBg = if (darkTheme) Color(0xFF1C1A18) else CreamLight
    val primaryText = if (darkTheme) Color(0xFFEBE6DF) else CharcoalSoft
    val secondaryText = if (darkTheme) Color(0xFF8E7E72) else WarmBrownDark
    val borderColor = if (darkTheme) Color(0xFF2A2623) else CreamDark

    var dbCategories by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    val userCreatedFolders = remember { mutableStateListOf<Pair<String, String>>() }
    var userNote by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val dbCats = withContext(Dispatchers.IO) {
            runCatching { db.stashItemDao().getDistinctCategories() }.getOrElse { emptyList() }
        }
        dbCategories = dbCats
    }

    val folderTypeMap = remember(userCreatedFolders.size) {
        userCreatedFolders.toMap()
    }
    
    val getFolderEmoji = { catName: String ->
        val type = folderTypeMap[catName].orEmpty()
        when {
            type == "Recipe" || catName.lowercase() == "recipes" -> "🍳"
            type == "Shopping" || catName.lowercase() == "shopping" || catName.lowercase() == "shoppings" -> "🛍️"
            type == "Trip" || catName.lowercase() == "trips" -> "✈️"
            type == "Ideas" || catName.lowercase() == "ideas" -> "💡"
            type == "Books" || catName.lowercase() == "books" -> "📚"
            type == "Learning" || catName.lowercase() == "learning" -> "🧠"
            type == "Coding" || catName.lowercase() == "coding" -> "💻"
            else -> "📁"
        }
    }

    val getCircleColor = { catName: String ->
        val type = folderTypeMap[catName].orEmpty()
        when {
            type == "Recipe" || catName.lowercase() == "recipes" -> Color(0xFFFF9500)
            type == "Shopping" || catName.lowercase() == "shopping" || catName.lowercase() == "shoppings" -> Color(0xFF34C759)
            type == "Trip" || catName.lowercase() == "trips" -> Color(0xFF007AFF)
            type == "Ideas" || catName.lowercase() == "ideas" -> Color(0xFFFFCC00)
            type == "Books" || catName.lowercase() == "books" -> Color(0xFF5856D6)
            type == "Learning" || catName.lowercase() == "learning" -> Color(0xFFFF2D55)
            type == "Coding" || catName.lowercase() == "coding" -> Color(0xFF8E8E93)
            else -> Color(0xFFAF52DE)
        }
    }

    val allCategories = remember(categories, dbCategories, userCreatedFolders.size) {
        val userCats = userCreatedFolders.map { it.first }
        (categories + dbCategories + userCats).distinct().sorted()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(screenBg)
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(borderColor)
        )
        
        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Save to Stash",
                    fontSize = 24.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = primaryText,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "Select a folder to store this item",
                    fontSize = 13.sp,
                    color = secondaryText,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            Surface(
                modifier = Modifier
                    .bounceClick { onSkip(userNote.takeIf { it.isNotBlank() }) }
                    .height(36.dp),
                shape = RoundedCornerShape(18.dp),
                color = cardBg.copy(alpha = 0.8f),
                border = BorderStroke(1.dp, borderColor.copy(alpha = 0.5f))
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Skip",
                        color = if (darkTheme) Color(0xFFE8A0C8) else WarmBrownDark,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 32.dp)
        ) {
            items(allCategories) { category ->
                val emoji = getFolderEmoji(category)
                val circleColor = getCircleColor(category)
                Surface(
                    modifier = Modifier
                        .bounceClick { onCategorySelected(category, userNote.takeIf { it.isNotBlank() }) }
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = cardBg.copy(alpha = 0.8f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(circleColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = category,
                            fontSize = 13.sp,
                            color = primaryText,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // New Folder Option
            item {
                Surface(
                    modifier = Modifier
                        .bounceClick { showCreateFolderDialog = true }
                        .fillMaxWidth()
                        .height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = cardBg.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color(0xFF8E8E93).copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("➕", fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "New Folder",
                            fontSize = 13.sp,
                            color = if (darkTheme) Color(0xFFE8A0C8) else WarmBrownDark,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = userNote,
            onValueChange = { userNote = it },
            placeholder = { 
                Text(
                    text = "Add a personal note...",
                    color = secondaryText,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontSize = 14.sp
                ) 
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = borderColor.copy(alpha = 0.5f),
                unfocusedBorderColor = borderColor.copy(alpha = 0.3f),
                focusedContainerColor = cardBg.copy(alpha = 0.5f),
                unfocusedContainerColor = cardBg.copy(alpha = 0.3f),
                focusedTextColor = primaryText,
                unfocusedTextColor = primaryText
            ),
            shape = RoundedCornerShape(12.dp),
            maxLines = 3
        )
    }

    if (showCreateFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        var selectedFolderType by remember { mutableStateOf("Folder") }
        
        AlertDialog(
            onDismissRequest = { showCreateFolderDialog = false },
            title = {
                Text(
                    text = "Create New Folder / Type",
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    color = primaryText
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("Folder Name", color = secondaryText) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryText,
                            unfocusedBorderColor = borderColor,
                            focusedTextColor = primaryText,
                            unfocusedTextColor = primaryText
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Text("Select Type:", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = primaryText)
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val types = listOf(
                            Pair("Recipe", "🍳"),
                            Pair("Shopping", "🛍️"),
                            Pair("Trip", "✈️"),
                            Pair("Folder", "📁")
                        )
                        types.forEach { (typeVal, emoji) ->
                            val isSelected = selectedFolderType == typeVal
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedFolderType = typeVal },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) primaryText else cardBg,
                                border = if (!isSelected) BorderStroke(1.dp, borderColor) else null
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Text(emoji, fontSize = 18.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = typeVal,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (isSelected) screenBg else primaryText
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val nameClean = newFolderName.trim()
                        if (nameClean.isNotEmpty()) {
                            userCreatedFolders.add(Pair(nameClean, selectedFolderType))
                            onCategorySelected(nameClean, userNote.takeIf { it.isNotBlank() })
                        }
                        showCreateFolderDialog = false
                    }
                ) {
                    Text("Create & Save", color = if (darkTheme) Color(0xFFE8A0C8) else WarmBrownDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolderDialog = false }) {
                    Text("Cancel", color = secondaryText)
                }
            },
            containerColor = cardBg,
            shape = RoundedCornerShape(24.dp)
        )
    }
}
