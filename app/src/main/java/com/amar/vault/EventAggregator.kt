package com.amar.vault

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only projection of a topic's existing references into real-world "events".
 *
 * It builds **no new knowledge, no storage, no index, no background pipeline** (the dummy
 * `EventBuildWorker`/`EventSnapshot` anchor-discovery pipeline is deliberately NOT resurrected —
 * that would be a new pipeline). Instead it reuses [EntityAggregator] (which reuses the existing
 * `RetrievalService`), then groups the returned [VaultItem]s into events by **time proximity** — a
 * deterministic heuristic over existing timestamps, not AI grouping.
 *
 * Titles are deterministic templates (topic + date span); a human-readable "Bought Laptop" title
 * would require summarization (AI) and is intentionally out of scope for this MVP.
 */
data class EventProjection(
    val id: String,
    val title: String,
    /** Entity type when the topic resolves to a canonical entity; null otherwise. */
    val eventType: String?,
    /** Representative (most recent) date of the event. */
    val date: Long?,
    val items: List<VaultItem>,
    val referenceCount: Int,
    val firstSeen: Long?,
    val lastUpdated: Long?,
    val documents: List<VaultItem>,
    val screenshots: List<VaultItem>,
    val images: List<VaultItem>,
)

class EventAggregator(private val entityAggregator: EntityAggregator) {

    private val docTypes = setOf("pdf", "word", "excel", "epub")

    /** Items whose timestamps fall within this gap of each other belong to the same event. */
    private val gapMs = 3L * 24 * 60 * 60 * 1000 // 3 days

    suspend fun eventsForTopic(name: String): List<EventProjection> {
        val profile = entityAggregator.profile(name)              // reuses retrieval + Entity Pages
        val dated = profile.chronological.filter { it.timestamp > 0 }.sortedBy { it.timestamp }
        val undated = profile.chronological.filter { it.timestamp <= 0 }

        // Cluster consecutive items whose gap is within [gapMs] into one event.
        val clusters = mutableListOf<MutableList<VaultItem>>()
        for (item in dated) {
            val last = clusters.lastOrNull()
            if (last == null || item.timestamp - last.last().timestamp > gapMs) {
                clusters.add(mutableListOf(item))
            } else {
                last.add(item)
            }
        }
        if (undated.isNotEmpty()) clusters.add(undated.toMutableList())

        return clusters
            .map { buildProjection(profile, it) }
            .sortedByDescending { it.date ?: Long.MIN_VALUE }      // newest event first
    }

    private fun buildProjection(profile: EntityProfile, cluster: List<VaultItem>): EventProjection {
        val ts = cluster.map { it.timestamp }.filter { it > 0 }
        val first = ts.minOrNull()
        val last = ts.maxOrNull()
        val chronological = cluster.sortedByDescending { it.timestamp }
        return EventProjection(
            id = "${profile.name}_${first ?: 0L}",
            title = buildTitle(profile.name, first, last),
            eventType = profile.entityType,
            date = last,
            items = chronological,
            referenceCount = cluster.size,
            firstSeen = first,
            lastUpdated = last,
            documents = cluster.filter { it.isDocumentPiece },
            screenshots = cluster.filter { !it.isDocumentPiece && it.itemType == ItemType.SCREENSHOT },
            images = cluster.filter { !it.isDocumentPiece && it.itemType != ItemType.SCREENSHOT },
        )
    }

    private fun buildTitle(name: String, first: Long?, last: Long?): String = when {
        first == null -> name
        last == null || fmt(first) == fmt(last) -> "$name — ${fmt(first)}"
        else -> "$name — ${fmt(first)} – ${fmt(last)}"
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ts))
}
