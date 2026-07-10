package com.amar.vault.timeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ContentOpenManager
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import com.amar.vault.timeline.model.TimelineEntrySnapshot
import com.amar.vault.timeline.model.TimelineEntryType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

/**
 * Presentation-only projection of a persisted [TimelineEntrySnapshot]: the snapshot plus the
 * VaultItem its first evidence id resolves to (looked up at read time — nothing is duplicated
 * into the timeline schema).
 */
data class TimelineEntryUi(
    val entry: TimelineEntrySnapshot,
    val item: VaultItem?,
)

/**
 * Reads the latest FRESH timeline snapshot and its entries from the existing DAOs, then resolves
 * each entry's evidence ids into existing VaultItems for display. No new persistence, no schema
 * change — the engine, snapshots, ordering, timestamps and confidence are consumed as-is.
 */
class TimelineViewModel {

    private val _pagedEntries = MutableStateFlow<List<TimelineEntryUi>>(emptyList())
    val pagedEntries: StateFlow<List<TimelineEntryUi>> = _pagedEntries.asStateFlow()

    private val _unknownDateEntries = MutableStateFlow<List<TimelineEntryUi>>(emptyList())
    val unknownDateEntries: StateFlow<List<TimelineEntryUi>> = _unknownDateEntries.asStateFlow()

    val PAGE_SIZE = 50

    suspend fun load(db: VaultDatabase) {
        val snapshot = db.timelineSnapshotDao().getLatestFreshTimeline()
        if (snapshot == null) {
            _pagedEntries.value = emptyList()
            _unknownDateEntries.value = emptyList()
            return
        }

        val entryDao = db.timelineEntrySnapshotDao()
        // getPagedEntries orders by timestamp DESC (dated entries first); dated vs unknown are
        // rendered in separate buckets, so keep only dated ones here to avoid double display.
        val known = entryDao.getPagedEntries(snapshot.timelineId, PAGE_SIZE, 0)
            .filter { it.timestamp != null }
        val unknown = entryDao.getUnknownDateEntries(snapshot.timelineId)

        // Batch-resolve every referenced VaultItem in one query, then enrich.
        val allIds = (known + unknown).flatMap { parseEvidenceIds(it.evidenceIdsJson) }.distinct()
        val itemsById = if (allIds.isEmpty()) emptyMap()
        else db.vaultDao().getByIds(allIds).associateBy { it.id }

        fun enrich(rows: List<TimelineEntrySnapshot>): List<TimelineEntryUi> = rows.map { e ->
            val firstId = parseEvidenceIds(e.evidenceIdsJson).firstOrNull()
            TimelineEntryUi(e, firstId?.let { itemsById[it] })
        }

        _pagedEntries.value = enrich(known)
        _unknownDateEntries.value = enrich(unknown)
    }

    private fun parseEvidenceIds(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
fun TimelineScreen() {
    val context = LocalContext.current
    val viewModel = remember { TimelineViewModel() }
    LaunchedEffect(Unit) { viewModel.load(VaultDatabase.get(context)) }

    val entries by viewModel.pagedEntries.collectAsState()
    val unknownEntries by viewModel.unknownDateEntries.collectAsState()

    // Grouping by Month/Year (dated entries).
    val groupedEntries = entries.groupBy { ui ->
        val ts = ui.entry.timestamp
        if (ts != null) {
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(ts))
        } else {
            "Unknown Date"
        }
    }

    if (entries.isEmpty() && unknownEntries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text("Your timeline is being prepared…\nCapture or index items and check back.", fontSize = 14.sp)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        groupedEntries.forEach { (month, monthEntries) ->
            item {
                Text(
                    text = month,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(monthEntries) { ui ->
                TimelineEntryRow(ui) { item -> ContentOpenManager.open(context, item) }
            }
        }

        // Unknown Dates appended at the absolute bottom.
        if (unknownEntries.isNotEmpty()) {
            item {
                Text(
                    text = "Unknown Date",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            items(unknownEntries) { ui ->
                TimelineEntryRow(ui) { item -> ContentOpenManager.open(context, item) }
            }
        }
    }
}

@Composable
fun TimelineEntryRow(ui: TimelineEntryUi, onOpen: (VaultItem) -> Unit) {
    val item = ui.item
    val icon = iconFor(ui.entry.entryType, item?.itemType)
    val title = displayTitle(ui.entry, item)
    val dateStr = ui.entry.timestamp?.let {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it))
    }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
        // Clicking opens the underlying document via existing navigation (when resolvable).
        .clickable(enabled = item != null) { item?.let(onOpen) }
    ) {
        Text(text = "$icon  $title", fontWeight = FontWeight.SemiBold)
        val caption = listOfNotNull(item?.itemType?.takeIf { it.isNotBlank() }, dateStr)
            .joinToString(" · ")
        if (caption.isNotBlank()) {
            Text(text = caption, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Emoji icon by entry/item type (mirrors the icons already used in search answers). */
private fun iconFor(entryType: String, itemType: String?): String {
    if (entryType == TimelineEntryType.EVENT.name) return "📅"
    return when (itemType) {
        "pdf" -> "📄"; "word" -> "📝"; "excel" -> "📊"
        "screenshot" -> "📸"; "epub" -> "📖"
        else -> "🖼"
    }
}

/** Real, human-readable title resolved from the VaultItem, falling back to the snapshot's label. */
private fun displayTitle(entry: TimelineEntrySnapshot, item: VaultItem?): String {
    if (item != null) {
        item.title?.takeIf { it.isNotBlank() }?.let { return it }
        item.sourceFile.takeIf { it.isNotBlank() }?.let { return it }
        val firstLine = item.ocrText.substringBefore("\n[").lineSequence()
            .map { it.trim() }.firstOrNull { it.isNotBlank() }
        if (!firstLine.isNullOrBlank()) return firstLine.take(80)
    }
    return entry.title
}
