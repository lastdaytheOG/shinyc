package com.amar.vault

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import com.amar.vault.ui.theme.ChevronGray

@Composable
fun AgenticScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onOpenItem: (String) -> Unit = {},
    onModelManagementClick: () -> Unit = {},
    onOnboardingClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val activeModel by remember {
        ModelManager.getInstance(context).getEnabledModelFlow()
    }.collectAsStateWithLifecycle(initialValue = null)

    val isModelReady = activeModel != null && activeModel?.status == "READY"
    val showFasterAiBanner by viewModel.showFasterAiBanner.collectAsStateWithLifecycle()

    LaunchedEffect(messages.size, isGenerating) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun sendQuery(query: String) {
        if (query.isBlank()) return
        val q = query.trim()
        keyboard?.hide()

        viewModel.cancelGeneration()
        viewModel.sendAgenticQuery(q)
    }

    if (!isModelReady) {
        // AI model required block screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Cream)
        ) {
            Spacer(Modifier.height(54.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← Home", color = WarmBrown, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = "AI model required",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = CharcoalSoft
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "To answer questions on-device, you need to download a local language model first.",
                        fontSize = 14.sp,
                        color = WarmBrown,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onOnboardingClick,
                        colors = ButtonDefaults.buttonColors(containerColor = CharcoalSoft),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text("Download Model", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    } else {
        var inputText by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Cream)
                .imePadding()
        ) {
            // Header bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        viewModel.cancelGeneration()
                        onBack()
                    }
                ) {
                    Text("← Home", color = WarmBrown, fontSize = 15.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Ask Vault",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CharcoalSoft
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onModelManagementClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Model Settings",
                        tint = WarmBrown,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (showFasterAiBanner) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = CreamLight),
                    border = BorderStroke(1.dp, CreamDark)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "A faster AI is available for your device.",
                            fontSize = 14.sp,
                            color = CharcoalSoft,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                viewModel.dismissFasterAiBanner()
                                onOnboardingClick()
                            }
                        ) {
                            Text("Update", color = WarmBrown, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { viewModel.dismissFasterAiBanner() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("×", fontSize = 24.sp, color = WarmBrown)
                        }
                    }
                }
            }

            // Chat area
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillParentMaxHeight(0.8f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Ask about your vault",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = WarmBrown,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                items(messages) { msg ->
                    if (msg.isUser) {
                        UserBubble(msg.text)
                    } else {
                        AgentBubble(msg = msg, onOpenItem = onOpenItem)
                    }
                }

                if (isGenerating) {
                    item { ThinkingBubble() }
                }
            }

            // Input bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CreamLight)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask a question…", color = ChevronGray, fontSize = 15.sp) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Cream,
                        unfocusedContainerColor = Cream,
                        focusedBorderColor = CreamDark,
                        unfocusedBorderColor = CreamDark
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        if (inputText.isNotBlank()) {
                            sendQuery(inputText)
                            inputText = ""
                        }
                    })
                )
                Spacer(Modifier.width(10.dp))
                Surface(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            sendQuery(inputText)
                            inputText = ""
                        }
                    },
                    shape = CircleShape,
                    color = if (inputText.isNotBlank()) CharcoalSoft else CreamDark,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "→",
                            fontSize = 20.sp,
                            color = if (inputText.isNotBlank()) Color.White else WarmBrown
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                .background(CharcoalSoft)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(0.85f)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
private fun AgentBubble(
    msg: ChatMessage,
    onOpenItem: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(0.9f)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
                .background(CreamLight)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Strip the fallback category markers (e.g. ⚡ or 🧠 or 🔍) from start of answer text for clean Apple look
            val cleanedText = msg.text
                .replaceFirst(Regex("^⚡\\s*"), "")
                .replaceFirst(Regex("^🧠\\s*"), "")
                .replaceFirst(Regex("^🔍\\s*"), "")
            Text(
                text = cleanedText,
                color = CharcoalSoft,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }

        if (msg.sources.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Sources Used",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = WarmBrown,
                modifier = Modifier.padding(start = 4.dp)
            )
            Spacer(Modifier.height(6.dp))
            msg.sources.forEach { source ->
                SourceItemRow(source = source, onOpen = { onOpenItem(source.id) })
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SourceItemRow(
    source: VaultItem,
    onOpen: () -> Unit
) {
    val title = source.sourceFile.takeIf { it.isNotBlank() } ?: when (source.itemType) {
        ItemType.SCREENSHOT -> "Screenshot"
        ItemType.PHOTO -> "Photo"
        ItemType.LINK -> when (LinkSite.of(source.uri)) {
            LinkSite.YOUTUBE -> "YouTube Video"
            LinkSite.REDDIT -> "Reddit Post"
            LinkSite.OTHER -> "Document"
        }
        ItemType.TEXT -> "Shared Note"
        ItemType.PDF -> "PDF Document"
        else -> "Document"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = CreamLight),
        border = BorderStroke(1.dp, CreamDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = CharcoalSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatRelativeTimeShort(source.timestamp),
                    fontSize = 11.sp,
                    color = WarmBrown
                )
            }
            TextButton(
                onClick = onOpen,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = "Open",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = CharcoalSoft
                )
            }
        }
    }
}

private fun formatRelativeTimeShort(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    val sdf = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault())
    return sdf.format(date)
}

@Composable
private fun ThinkingBubble() {
    val inf = rememberInfiniteTransition(label = "think")
    val d1 by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "d1")
    val d2 by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, delayMillis = 150), RepeatMode.Reverse), label = "d2")
    val d3 by inf.animateFloat(0.3f, 1f, infiniteRepeatable(tween(500, delayMillis = 300), RepeatMode.Reverse), label = "d3")

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp))
            .background(CreamLight)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(d1, d2, d3).forEach { a ->
                Box(Modifier.size(8.dp).clip(CircleShape).background(WarmBrown.copy(alpha = a)))
            }
        }
    }
}