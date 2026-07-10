package com.amar.vault.events.engine

import com.amar.vault.VaultDatabase
import com.amar.vault.VaultRelationship
import com.amar.vault.events.model.EventSnapshot
import com.amar.vault.events.model.EventSnapshotState
import com.amar.vault.events.model.EventState
import org.json.JSONArray
import java.security.MessageDigest

object EventBuilder {
    private const val MAX_EVENT_EVIDENCE = 100

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun buildEvent(
        eventId: String,
        anchorType: String,
        anchorConfidence: Float,
        relationships: List<VaultRelationship>,
        relationshipVersion: String,
        classificationVersion: String,
        canonicalizationVersion: String,
        eventVersion: String,
        db: VaultDatabase
    ): EventSnapshot? {
        
        // 1. Collect Evidence 
        // Strict inclusion rule: ONLY CONFIRMED relationships and Exact LOCATION
        val validEvidence = relationships.filter {
            it.relationshipState == "CONFIRMED" || it.relationshipType == "EXACT_LOCATION"
        }

        if (validEvidence.isEmpty()) {
            return null // Anchor exists but evidence count == 0
        }

        // 2. Build Confidence (Pass full evidence set)
        val confidence = EventConfidenceEngine.calculateConfidence(anchorConfidence, validEvidence)

        // Acceptance Rule: Confidence >= 0.75
        if (confidence < 0.75f) {
            return null // DISCARD logic: never instantiate discarded event objects
        }

        // 3. Sort Evidence By Contribution
        // Priority (simplified for now based on rules): 
        // Anchor -> Tickets -> Hotels -> Payments -> Attachments -> Location
        val sortedEvidence = validEvidence.sortedByDescending { it.confidence }

        // 4. Truncate Evidence
        val truncatedEvidence = sortedEvidence.take(MAX_EVENT_EVIDENCE)
        val isEvidenceTruncated = validEvidence.size > MAX_EVENT_EVIDENCE

        // Track event purity evidence
        val confirmedRelationshipCount = validEvidence.count { it.relationshipState == "CONFIRMED" }
        val locationEvidenceCount = validEvidence.count { it.relationshipType == "EXACT_LOCATION" }

        val evidenceIds = truncatedEvidence.map { it.targetId } // Assuming targetId is the evidence ID
        val evidenceIdsJson = JSONArray(evidenceIds).toString()

        val eventBuildContextHash = sha256(relationshipVersion + classificationVersion + canonicalizationVersion + eventVersion)

        val snapshot = EventSnapshot(
            eventId = eventId,
            anchorType = anchorType,
            anchorConfidence = anchorConfidence,
            isEvidenceTruncated = isEvidenceTruncated,
            evidenceCount = validEvidence.size, // Original evidence count
            confirmedRelationshipCount = confirmedRelationshipCount,
            locationEvidenceCount = locationEvidenceCount,
            evidenceIdsJson = evidenceIdsJson,
            buildTimeMs = System.currentTimeMillis(), // We'll compute delta below
            eventBuildContextHash = eventBuildContextHash,
            relationshipVersion = relationshipVersion,
            classificationVersion = classificationVersion,
            canonicalizationVersion = canonicalizationVersion,
            eventVersion = eventVersion,
            snapshotState = EventSnapshotState.FRESH,
            retryCount = 0,
            lastFailureReason = null
        )
        
        // Store
        db.eventSnapshotDao().upsert(snapshot)
        return snapshot
    }
}
