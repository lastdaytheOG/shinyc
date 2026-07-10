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
 * Events MVP — a read-only list of time-clustered events for a topic. Each event expands to show a
 * chronological summary and its documents/screenshots/images; tapping an item opens the source.
 * All data comes from [SearchViewModel.eventsForTopic] (the existing retrieval engine). No AI,
 * no graph, no summarization.
 */
@Composable
fun EventsScreen(
    viewModel: SearchViewModel,
    topic: String,
    onOpen: (VaultItem) -> Unit,
) {
    var events by remember(topic) { mutableStateOf<List<EventProjection>?>(null) }
    LaunchedEffect(topic) { events = viewModel.eventsForTopic(topic) }

    val list = events
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Grouping related items into events…", fontSize = 14.sp)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Events", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Text(topic, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        }
        if (list.isEmpty()) {
            item {
                Text(
                    "No events found for \"$topic\".",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 24.dp)
                )
            }
        } else {
            items(list, key = { it.id }) { event ->
                EventCard(event, onOpen)
            }
        }
    }
}

@Composable
private fun EventCard(event: EventProjection, onOpen: (VaultItem) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)
        .clickable { expanded = !expanded }
    ) {
        // ── Title + summary (the temporal "timeline" span) ───────────────────
        Text(event.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        val summary = buildString {
            append("${event.referenceCount} item${if (event.referenceCount == 1) "" else "s"}")
            event.eventType?.let { append(" · $it") }
            if (event.documents.isNotEmpty()) append(" · ${event.documents.size} doc")
            if (event.screenshots.isNotEmpty()) append(" · ${event.screenshots.size} screenshot")
            if (event.images.isNotEmpty()) append(" · ${event.images.size} image")
        }
        Text(summary, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))

        if (expanded) {
            // Sections mirror the Entity Page layout; each row opens the source item.
            EventSection("Documents", event.documents, onOpen)
            EventSection("Screenshots", event.screenshots, onOpen)
            EventSection("Images", event.images, onOpen)
        }
    }
}

@Composable
private fun EventSection(title: String, items: List<VaultItem>, onOpen: (VaultItem) -> Unit) {
    if (items.isEmpty()) return
    Text(
        "$title (${items.size})",
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
    )
    items.forEach { item ->
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            .clickable { onOpen(item) }
        ) {
            Text("${iconFor(item.itemType)}  ${titleFor(item)}", fontSize = 14.sp)
            val caption = listOfNotNull(
                item.itemType.takeIf { it.isNotBlank() },
                item.timestamp.takeIf { it > 0 }?.let { fmtDate(it) },
            ).joinToString(" · ")
            if (caption.isNotBlank()) {
                Text(caption, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
            }
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
