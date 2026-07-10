package com.amar.vault.memory.engine

import com.amar.vault.VaultDatabase
import com.amar.vault.memory.model.MemoryState
import com.amar.vault.memory.model.MemoryType
import com.amar.vault.memory.storage.AgentMemorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

object AgentMemoryBuilder {

    const val MEMORY_VERSION = "v1"

    suspend fun rebuildMemories(
        db: VaultDatabase,
        relationshipVersion: String,
        classificationVersion: String,
        canonicalizationVersion: String,
        eventVersion: String,
        timelineVersion: String
    ) = withContext(Dispatchers.IO) {
        
        // 1. O(1) Context Hashing
        val rawContext = "$relationshipVersion|$classificationVersion|$canonicalizationVersion|$eventVersion|$timelineVersion|$MEMORY_VERSION"
        val contextHash = hashString(rawContext)

        val newMemories = mutableListOf<AgentMemorySnapshot>()

        // 2. Fetch CONFIRMED Timeline Entries (Mocked Logic for Builder Pipeline)
        // val timelineEntries = db.timelineEntrySnapshotDao().getPagedEntries(timelineId, limit=1000)

        // Pattern 1: Recurring Payment (Minimum 3 occurrences)
        // Mocking a detected Swiggy pattern
        val swiggyCount = 12
        if (swiggyCount >= 3) {
            val confidence = MemoryConfidenceEngine.evaluateConfidence(0.98f, swiggyCount, 3, 1.0f)
            if (MemoryConfidenceEngine.determineState(confidence) == MemoryState.CONFIRMED) {
                newMemories.add(
                    AgentMemorySnapshot(
                        memoryId = "mem_${UUID.randomUUID()}",
                        memoryType = MemoryType.RECURRING_PAYMENT.name,
                        memoryState = MemoryState.CONFIRMED.name,
                        confidence = confidence,
                        occurrenceCount = swiggyCount,
                        supportingEventCount = 0,
                        supportingRelationshipCount = 12,
                        firstSeen = 1768100000000L, // Jan 2026
                        lastSeen = 1778100000000L,  // May 2026
                        summary = MemorySummaryFormatter.formatSummary(
                            MemoryType.RECURRING_PAYMENT, "Swiggy", swiggyCount, 1768100000000L, 1778100000000L
                        ),
                        evidenceIdsJson = JSONArray().put("pay_1").put("pay_2").toString(),
                        memoryVersion = MEMORY_VERSION,
                        memoryContextHash = contextHash
                    )
                )
            }
        }

        // Pattern 2: Recurring Travel (Minimum 2 confirmed events)
        val goaCount = 3
        if (goaCount >= 2) {
            val confidence = MemoryConfidenceEngine.evaluateConfidence(0.99f, goaCount, 2, 1.0f)
            if (MemoryConfidenceEngine.determineState(confidence) == MemoryState.CONFIRMED) {
                newMemories.add(
                    AgentMemorySnapshot(
                        memoryId = "mem_${UUID.randomUUID()}",
                        memoryType = MemoryType.RECURRING_TRAVEL.name,
                        memoryState = MemoryState.CONFIRMED.name,
                        confidence = confidence,
                        occurrenceCount = goaCount,
                        supportingEventCount = 3,
                        supportingRelationshipCount = 15,
                        firstSeen = 1730000000000L, 
                        lastSeen = 1760000000000L,
                        summary = MemorySummaryFormatter.formatSummary(
                            MemoryType.RECURRING_TRAVEL, "Goa", goaCount, 1730000000000L, 1760000000000L
                        ),
                        evidenceIdsJson = JSONArray().put("evt_goa_1").put("evt_goa_2").toString(),
                        memoryVersion = MEMORY_VERSION,
                        memoryContextHash = contextHash
                    )
                )
            }
        }

        // 3. Degradation Lifecycle Check
        // val existingMemories = db.agentMemorySnapshotDao().getConfirmedMemories()
        // for (mem in existingMemories) {
        //     if (mem.memoryContextHash != contextHash) {
        //         db.agentMemorySnapshotDao().markMemoriesStale(listOf(mem.memoryId))
        //     }
        // }

        // 4. Persistence
        // db.agentMemorySnapshotDao().insertAll(newMemories)
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
