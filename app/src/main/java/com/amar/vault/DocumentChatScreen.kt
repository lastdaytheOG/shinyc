package com.amar.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Document Chat MVP — a grounded conversation scoped to exactly one [VaultItem].
 *
 * Every answer goes through [SearchViewModel.chatWithDocument] → the existing [RagService.executeRag]
 * with ONLY this document as the source (no cross-document retrieval, no second execution path). The
 * conversation is in-memory only (no new storage). Existing document viewing is untouched.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentChatScreen(
    itemId: String,
    viewModel: SearchViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var item by remember(itemId) { mutableStateOf<VaultItem?>(null) }
    LaunchedEffect(itemId) {
        item = withContext(Dispatchers.IO) {
            VaultDatabase.get(context).vaultDao().getByIds(listOf(itemId)).firstOrNull()
        }
    }

    var conversation by remember(itemId) { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var input by remember(itemId) { mutableStateOf("") }
    var generating by remember(itemId) { mutableStateOf(false) }
    var lastQuestion by remember(itemId) { mutableStateOf<String?>(null) }

    fun ask(question: String) {
        val doc = item ?: return
        val q = question.trim()
        if (q.isBlank() || generating) return
        val prior = conversation
        conversation = conversation + ChatMessage(q, isUser = true)
        lastQuestion = q
        input = ""
        generating = true
        scope.launch {
            val answer = viewModel.chatWithDocument(doc, prior, q)
            conversation = conversation + ChatMessage(answer.ifBlank { UNAVAILABLE }, isUser = false)
            generating = false
        }
    }

    fun regenerate() {
        val doc = item ?: return
        val q = lastQuestion ?: return
        if (generating) return
        // Drop the previous assistant reply (if any) and re-answer the last question.
        val trimmed = if (conversation.lastOrNull()?.isUser == false) conversation.dropLast(1) else conversation
        conversation = trimmed
        val prior = if (trimmed.lastOrNull()?.isUser == true) trimmed.dropLast(1) else trimmed
        generating = true
        scope.launch {
            val answer = viewModel.chatWithDocument(doc, prior, q)
            conversation = trimmed + ChatMessage(answer.ifBlank { UNAVAILABLE }, isUser = false)
            generating = false
        }
    }

    val docTitle = item?.title?.takeIf { it.isNotBlank() }
        ?: item?.sourceFile?.takeIf { it.isNotBlank() }
        ?: item?.itemType?.uppercase() ?: "DOCUMENT"

    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = { Text("Chat · $docTitle", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CharcoalSoft) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = WarmBrownDark)
                    }
                },
                actions = {
                    if (conversation.isNotEmpty()) {
                        IconButton(onClick = { conversation = emptyList(); lastQuestion = null }) {
                            Icon(Icons.Default.Delete, contentDescription = "Clear conversation", tint = WarmBrownDark)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (conversation.isEmpty() && !generating) {
                    item {
                        Text(
                            "Ask a question about this document. Answers use only this document.",
                            fontSize = 14.sp,
                            color = WarmBrownDark,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }
                }
                items(conversation) { msg -> MessageBubble(msg) }
                if (generating) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = WarmBrown, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking…", fontSize = 13.sp, color = WarmBrownDark)
                        }
                    }
                }
            }

            if (lastQuestion != null && !generating) {
                TextButton(onClick = { regenerate() }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Regenerate last response", color = WarmBrown, fontSize = 13.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about this document…", color = WarmBrown, fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CreamLight,
                        unfocusedContainerColor = CreamLight,
                        focusedBorderColor = WarmBrown,
                        unfocusedBorderColor = CreamDark,
                    )
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { ask(input) },
                    enabled = !generating && input.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = WarmBrown)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val alignment = if (msg.isUser) Alignment.End else Alignment.Start
    val bg = if (msg.isUser) WarmBrown else CreamLight
    val fg = if (msg.isUser) Color.White else CharcoalSoft
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(msg.text, fontSize = 14.sp, color = fg, lineHeight = 20.sp)
        }
    }
}

private const val UNAVAILABLE =
    "AI model not available. Enable a local model in AI settings to chat."
