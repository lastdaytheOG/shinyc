package com.amar.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Entity Page MVP — a read-only view of everything the vault already knows about one topic.
 * All content comes from [SearchViewModel.entityProfile] (the existing retrieval engine); this
 * screen only presents it. No AI, no graph, no relationship explorer.
 */
@Composable
fun EntityScreen(
    viewModel: SearchViewModel,
    entityName: String,
    onOpen: (VaultItem) -> Unit,
    onEventsClick: (String) -> Unit = {},
) {
    var profile by remember(entityName) { mutableStateOf<EntityProfile?>(null) }
    LaunchedEffect(entityName) { profile = viewModel.entityProfile(entityName) }

    val p = profile
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Gathering what the vault knows…", fontSize = 14.sp)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        // ── Header: name + type ──────────────────────────────────────────────
        item {
            Text(p.name, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            p.entityType?.let {
                Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ── Summary statistics ───────────────────────────────────────────────
        item {
            val span = when {
                p.firstSeen != null && p.lastSeen != null ->
                    "${fmtDate(p.firstSeen)} → ${fmtDate(p.lastSeen)}"
                else -> "—"
            }
            Text(
                "${p.referenceCount} reference${if (p.referenceCount == 1) "" else "s"}  ·  $span",
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
            if (p.referenceCount > 0) {
                androidx.compose.material3.TextButton(onClick = { onEventsClick(p.name) }) {
                    Text("View events", fontSize = 14.sp)
                }
            }
        }

        if (p.referenceCount == 0) {
            item {
                Text(
                    "Nothing indexed about \"${p.name}\" yet.",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
            return@LazyColumn
        }

        section("Documents", p.documents, onOpen)
        section("Screenshots", p.screenshots, onOpen)
        section("Images", p.images, onOpen)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<VaultItem>,
    onOpen: (VaultItem) -> Unit,
) {
    if (items.isEmpty()) return
    item {
        Text(
            "$title (${items.size})",
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp)
        )
    }
    items(items, key = { it.id }) { item ->
        EntityItemRow(item, onOpen)
    }
}

@Composable
private fun EntityItemRow(item: VaultItem, onOpen: (VaultItem) -> Unit) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
        .clickable { onOpen(item) }
    ) {
        Text("${iconFor(item.itemType)}  ${titleFor(item)}", fontWeight = FontWeight.SemiBold)
        val caption = listOfNotNull(
            item.itemType.takeIf { it.isNotBlank() },
            item.timestamp.takeIf { it > 0 }?.let { fmtDate(it) },
        ).joinToString(" · ")
        if (caption.isNotBlank()) {
            Text(caption, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

private fun fmtDate(ts: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))

private fun iconFor(itemType: String): String = when (itemType) {
    "pdf" -> "📄"; "word" -> "📝"; "excel" -> "📊"
    "screenshot" -> "📸"; "epub" -> "📖"
    else -> "🖼"
}

private fun titleFor(item: VaultItem): String {
    item.title?.takeIf { it.isNotBlank() }?.let { return it }
    item.sourceFile.takeIf { it.isNotBlank() }?.let { return it }
    val firstLine = item.ocrText.substringBefore("\n[").lineSequence()
        .map { it.trim() }.firstOrNull { it.isNotBlank() }
    return firstLine?.take(80) ?: "Item"
}
