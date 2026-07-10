package com.amar.vault.reasoning.engine

import com.amar.vault.VaultDatabase
import com.amar.vault.reasoning.model.ReasoningSnapshotState
import com.amar.vault.reasoning.model.ReasoningState
import com.amar.vault.reasoning.model.ReasoningType
import com.amar.vault.reasoning.storage.ReasoningSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

object ReasoningBuilder {

    const val REASONING_VERSION = "v2"
    const val MAX_REASONINGS_PER_ENTITY = 20
    const val MAX_REASONING_EVIDENCE = 100

    suspend fun buildReasoning(
        db: VaultDatabase,
        relationshipVersion: String,
        classificationVersion: String,
        canonicalizationVersion: String,
        eventVersion: String,
        timelineVersion: String,
        memoryVersion: String
    ) = withContext(Dispatchers.IO) {
        
        val rawContext = "$relationshipVersion|$classificationVersion|$canonicalizationVersion|$eventVersion|$timelineVersion|$memoryVersion|$REASONING_VERSION"
        val contextHash = hashString(rawContext)

        val newArtifacts = mutableListOf<ReasoningSnapshot>()
        val contradictionLog = mutableMapOf<String, MutableList<ReasoningSnapshot>>()

        // --- MOCKED LOGIC FOR RULE_TREND_TRAVEL ---
        val mockTravelMemoryIds = listOf("mem_goa_1", "mem_goa_2")
        val entityId = "ent_goa"
        val period = "Q1 2026"
        
        if (mockTravelMemoryIds.size >= 2) { // Minimum 2 for TREND
            val confidence = ReasoningConfidenceEngine.evaluateConfidence(0.98f, 1.0f, mockTravelMemoryIds.size, 2)
            
            // Simulating build stability tracking. Let's assume this is build count 1.
            val buildCount = 1 
            val (businessState, snapshotState) = ReasoningConfidenceEngine.determineStates(confidence, buildCount)

            if (snapshotState != ReasoningSnapshotState.FAILED) {
                
                // Explosion Protection: Truncate Evidence
                var evidenceIds = listOf("evt_1", "evt_2")
                var isTruncated = false
                if (evidenceIds.size > MAX_REASONING_EVIDENCE) {
                    evidenceIds = evidenceIds.take(MAX_REASONING_EVIDENCE)
                    isTruncated = true
                }

                val artifact = ReasoningSnapshot(
                    reasoningId = "res_${UUID.randomUUID()}",
                    entityId = entityId,
                    period = period,
                    reasoningType = ReasoningType.TREND.name,
                    reasoningState = businessState?.name ?: "PENDING", // PENDING if internal candidate
                    snapshotState = snapshotState.name,
                    createdByRule = "RULE_TREND_TRAVEL",
                    confidence = confidence,
                    occurrenceCount = mockTravelMemoryIds.size,
                    summary = ReasoningSummaryFormatter.formatTrend("Travel activity", "frequency", 1, 3, "Q1", "Q2"),
                    supportingMemoryIdsJson = JSONArray(mockTravelMemoryIds).toString(),
                    evidenceIdsJson = JSONArray(evidenceIds).toString(),
                    isTruncated = isTruncated,
                    reasoningVersion = REASONING_VERSION,
                    reasoningContextHash = contextHash
                )
                
                val topicKey = "$entityId|${ReasoningType.TREND.name}|$period"
                contradictionLog.getOrPut(topicKey) { mutableListOf() }.add(artifact)
            }
        }

        // --- CONTRADICTION ABORT ---
        for ((topic, artifacts) in contradictionLog) {
            if (artifacts.size > 1) {
                // ABORT BOTH
                continue 
            } else {
                newArtifacts.addAll(artifacts)
            }
        }

        // Entity Cap Protection (Assuming single entity mock processing)
        val finalToInsert = if (newArtifacts.size > MAX_REASONINGS_PER_ENTITY) {
            newArtifacts.take(MAX_REASONINGS_PER_ENTITY)
        } else {
            newArtifacts
        }

        // db.reasoningSnapshotDao().insertAll(finalToInsert)
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
