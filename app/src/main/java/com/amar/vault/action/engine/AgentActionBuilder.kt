package com.amar.vault.action.engine

import com.amar.vault.VaultDatabase
import com.amar.vault.action.model.ActionState
import com.amar.vault.action.model.ActionUserState
import com.amar.vault.action.model.AgentActionType
import com.amar.vault.action.storage.AgentActionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.security.MessageDigest
import java.util.UUID

object AgentActionBuilder {

    const val ACTION_VERSION = "v2"
    const val MAX_ACTIONS_PER_ENTITY = 10
    const val MAX_ACTION_EVIDENCE = 100
    const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

    suspend fun buildActions(
        db: VaultDatabase,
        relationshipVersion: String,
        classificationVersion: String,
        canonicalizationVersion: String,
        eventVersion: String,
        timelineVersion: String,
        memoryVersion: String,
        reasoningVersion: String,
        currentTimeMs: Long
    ) = withContext(Dispatchers.IO) {

        val rawContext = "$relationshipVersion|$classificationVersion|$canonicalizationVersion|$eventVersion|$timelineVersion|$memoryVersion|$reasoningVersion|$ACTION_VERSION"
        val contextHash = hashString(rawContext)

        val newSnapshots = mutableListOf<AgentActionSnapshot>()

        // MOCKED LOGIC FOR INSIGHT
        val mockReasoningId = "res_trend_spend_1"
        val mockEntityId = "ent_swiggy"
        val mockPeriod = "Q2 2026"
        val supportingMemories = 5 // >= 3
        
        if (supportingMemories >= 3) {
            val confidence = AgentActionConfidenceEngine.evaluateConfidence(0.998f, 1.0f, supportingMemories)
            val buildCount = 1 
            val actionState = AgentActionConfidenceEngine.determineActionState(confidence, buildCount)

            if (actionState != ActionState.FAILED) {
                var evidenceIds = listOf("evt_1", "evt_2", "evt_3")
                var isTruncated = false
                if (evidenceIds.size > MAX_ACTION_EVIDENCE) {
                    evidenceIds = evidenceIds.take(MAX_ACTION_EVIDENCE)
                    isTruncated = true
                }

                // Expiration Logic: Trend Insights expire after 30 days.
                val expirationTime = currentTimeMs + THIRTY_DAYS_MS

                val action = AgentActionSnapshot(
                    actionId = "act_${UUID.randomUUID()}",
                    entityId = mockEntityId,
                    period = mockPeriod,
                    actionType = AgentActionType.INSIGHT.name,
                    actionState = actionState.name,
                    userState = ActionUserState.ACTIVE.name,
                    confidence = confidence,
                    summary = "Swiggy spending trend rising sharply over last 3 months.",
                    supportingReasoningIdsJson = JSONArray().put(mockReasoningId).toString(),
                    evidenceIdsJson = JSONArray(evidenceIds).toString(),
                    createdByRule = "RULE_INSIGHT_TREND",
                    occurrenceCount = supportingMemories,
                    isTruncated = isTruncated,
                    lastTriggeredAt = null,
                    cooldownDays = 14, 
                    generatedAt = currentTimeMs,      // Audit Trail
                    lastEvaluatedAt = currentTimeMs,  // Audit Trail
                    expiresAt = expirationTime,       // Action Hygiene Expiration
                    actionVersion = ACTION_VERSION,
                    actionContextHash = contextHash
                )
                
                newSnapshots.add(action)
            }
        }

        // db.agentActionSnapshotDao().insertAll(newSnapshots)
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
