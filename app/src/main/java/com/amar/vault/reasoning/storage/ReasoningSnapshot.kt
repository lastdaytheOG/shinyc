package com.amar.vault.reasoning.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reasoning_snapshot",
    indices = [
        Index(value = ["entityId", "reasoningType", "period"], unique = true)
    ]
)
data class ReasoningSnapshot(
    @PrimaryKey val reasoningId: String,
    val entityId: String,
    val period: String,
    val reasoningType: String,
    val reasoningState: String, // Always CONFIRMED per rule separation
    val snapshotState: String,  // FRESH, STALE, INTERNAL_CANDIDATE, FAILED
    val createdByRule: String,
    val confidence: Float,
    val occurrenceCount: Int,
    val summary: String,
    val supportingMemoryIdsJson: String,
    val evidenceIdsJson: String,
    val isTruncated: Boolean,
    val reasoningVersion: String,
    val reasoningContextHash: String
)
