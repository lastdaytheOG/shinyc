package com.amar.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.amar.vault.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentViewerScreen(
    itemId: String,
    onBack: () -> Unit,
    /** When provided, shows a read-only "Summarize" action powered by the existing local LLM. */
    summarize: (suspend (VaultItem) -> String)? = null,
    /** When provided, shows a "Chat with this document" action (grounded single-doc chat). */
    onChatClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf<VaultItem?>(null) }
    var metadataList by remember { mutableStateOf<List<VaultMetadata>>(emptyList()) }
    var showRawText by remember { mutableStateOf(false) }

    // Document Summary state (per item).
    var summaryText by remember(itemId) { mutableStateOf<String?>(null) }
    var summarizing by remember(itemId) { mutableStateOf(false) }

    // Load data from DB
    LaunchedEffect(itemId) {
        withContext(Dispatchers.IO) {
            val db = VaultDatabase.get(context)
            val fetchedItem = db.vaultDao().getByIds(listOf(itemId)).firstOrNull()
            val fetchedMeta = db.vaultMetadataDao().getByItemId(itemId)
            withContext(Dispatchers.Main) {
                item = fetchedItem
                metadataList = fetchedMeta
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.itemType?.stored?.uppercase() ?: "DOCUMENT", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = WarmBrownDark)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)
            )
        },
        containerColor = Cream
    ) { paddingValues ->
        if (item == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = WarmBrown)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
            ) {
                // Zoomable Image Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .background(Color(0xFFE5DCD0))
                ) {
                    var scale by remember { mutableStateOf(1f) }
                    var offsetX by remember { mutableStateOf(0f) }
                    var offsetY by remember { mutableStateOf(0f) }

                    val painter = rememberAsyncImagePainter(model = item!!.uri)

                    Image(
                        painter = painter,
                        contentDescription = "Document preview image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offsetX,
                                translationY = offsetY
                            )
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                    offsetX += pan.x * scale
                                    offsetY += pan.y * scale
                                }
                            }
                    )
                }

                // Metadata Details Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(CreamLight)
                        .border(1.dp, CreamDark, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = "METADATA ATTRIBUTES",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarmBrown,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(Modifier.height(12.dp))

                    if (metadataList.isEmpty()) {
                        Text(
                            text = "No structured metadata extracted for this item.",
                            fontSize = 14.sp,
                            color = WarmBrownDark
                        )
                    } else {
                        metadataList.forEach { meta ->
                            MetadataRow(label = meta.type, value = meta.value)
                            Divider(color = CreamDark, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // OCR Expand Button
                    Button(
                        onClick = { showRawText = !showRawText },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = WarmBrown),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (showRawText) "Hide Raw Extracted Text" else "Show Raw Extracted Text",
                            color = Color.White,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (showRawText) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CreamDark.copy(alpha = 0.3f))
                                .border(1.dp, CreamDark, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = item!!.ocrText.trim(),
                                fontSize = 13.sp,
                                color = CharcoalSoft,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    // ── Chat with this document (grounded single-doc chat) ───────
                    if (onChatClick != null) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onChatClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = WarmBrownDark),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Chat with this document", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }

                    // ── Document Summary (read-only, local LLM) ──────────────────
                    if (summarize != null) {
                        val cleanText = item!!.ocrText.trim()
                        val wordCount = cleanText.split(Regex("\\s+")).count { it.isNotBlank() }
                        val keyTopics = metadataList
                            .filter { it.type in setOf("DOCUMENT_CLASS", "DOCUMENT_TYPE", "CATEGORY", "ORGANIZATION") }
                            .map { it.value }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .take(6)

                        Spacer(Modifier.height(12.dp))
                        Divider(color = CreamDark, thickness = 0.5.dp)
                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "AI SUMMARY",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = WarmBrown,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "≈ $wordCount words" +
                                if (keyTopics.isNotEmpty()) "  ·  ${keyTopics.joinToString(" · ")}" else "",
                            fontSize = 12.sp,
                            color = WarmBrownDark,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                        )

                        Button(
                            onClick = {
                                val current = item ?: return@Button
                                summarizing = true
                                summaryText = null
                                scope.launch {
                                    val result = summarize(current)
                                    summaryText = result.ifBlank {
                                        "AI model not available. Enable a local model in AI settings to summarize."
                                    }
                                    summarizing = false
                                }
                            },
                            enabled = !summarizing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = WarmBrown),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = when {
                                    summarizing -> "Summarizing…"
                                    summaryText != null -> "Regenerate Summary"
                                    else -> "Summarize"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (summarizing) {
                            Spacer(Modifier.height(12.dp))
                            CircularProgressIndicator(color = WarmBrown, modifier = Modifier.size(24.dp))
                        } else summaryText?.let { summary ->
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CreamDark.copy(alpha = 0.3f))
                                    .border(1.dp, CreamDark, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = summary,
                                    fontSize = 14.sp,
                                    color = CharcoalSoft,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = WarmBrownDark
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = CharcoalSoft
        )
    }
}
