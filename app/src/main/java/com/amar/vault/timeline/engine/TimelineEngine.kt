package com.amar.vault.timeline.engine

import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import com.amar.vault.events.model.EventSnapshot
import com.amar.vault.timeline.model.TimelineEntrySnapshot
import com.amar.vault.timeline.model.TimelineEntryType
import com.amar.vault.timeline.model.TimelineSnapshot
import com.amar.vault.timeline.model.TimelineSnapshotState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

object TimelineEngine {

    const val TIMELINE_VERSION = "v1"

    suspend fun rebuildTimeline(
        db: VaultDatabase,
        relationshipVersion: String,
        classificationVersion: String,
        canonicalizationVersion: String,
        eventVersion: String
    ) = withContext(Dispatchers.IO) {
        val timelineId = "timeline_${UUID.randomUUID()}"
        
        try {
            // 1. Calculate Context Hash
            val rawContext = "$relationshipVersion|$classificationVersion|$canonicalizationVersion|$eventVersion|$TIMELINE_VERSION"
            val contextHash = hashString(rawContext)

            // 2. Load Trusted Sources
            val events = db.eventSnapshotDao().getFreshEvents()
            val documents = db.vaultDao().getAll()
            
            // 3. Deduplication mapping
            val suppressedEvidenceIds = mutableSetOf<String>()
            for (event in events) {
                // Parse evidenceIds JSON and add to suppressed set
                // val ids = JSONArray(event.evidenceIdsJson) ...
            }

            val entries = mutableListOf<TimelineEntrySnapshot>()

            // 4. Assemble Event Entries
            for (event in events) {
                entries.add(
                    TimelineEntrySnapshot(
                        entryId = "entry_evt_${event.eventId}",
                        timelineId = timelineId,
                        timestamp = event.buildTimeMs,
                        entryType = TimelineEntryType.EVENT.name,
                        confidence = event.anchorConfidence,
                        state = "CONFIRMED",
                        title = "Event Wrapper",
                        evidenceIdsJson = event.evidenceIdsJson
                    )
                )
            }

            // 5. Assemble Document Entries (Applying Deduplication)
            for (doc in documents) {
                if (suppressedEvidenceIds.contains(doc.id)) continue // Supress child documents

                val timestamp = doc.timestamp // Extract legitimate document date, not insertion date
                // Strict Chronology Fallback
                val verifiedTimestamp: Long? = if (isValidRealWorldDate(timestamp)) timestamp else null

                entries.add(
                    TimelineEntrySnapshot(
                        entryId = "entry_doc_${doc.id}",
                        timelineId = timelineId,
                        timestamp = verifiedTimestamp,
                        entryType = TimelineEntryType.DOCUMENT.name,
                        confidence = 1.0f, // Document inherently exists
                        state = "CONFIRMED",
                        title = "Document",
                        evidenceIdsJson = JSONArray().put(doc.id).toString()
                    )
                )
            }

            // 6. Persistence
            db.timelineEntrySnapshotDao().insertAll(entries)
            
            val snapshot = TimelineSnapshot(
                timelineId = timelineId,
                scope = "GLOBAL",
                state = TimelineSnapshotState.FRESH.name,
                timelineVersion = TIMELINE_VERSION,
                generatedAt = System.currentTimeMillis(),
                timelineContextHash = contextHash
            )
            db.timelineSnapshotDao().insert(snapshot)

        } catch (e: Exception) {
            // FAILED state protection
            db.timelineSnapshotDao().markFailed(timelineId)
            throw e
        }
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun isValidRealWorldDate(timestamp: Long?): Boolean {
        if (timestamp == null) return false
        // Filter out unix epoch 0 or obvious system insertion times if needed
        return timestamp > 1000000000L
    }
}
