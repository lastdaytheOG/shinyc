package com.amar.vault.reasoning.model

data class ReasoningArtifact(
    val reasoningId: String,
    val entityId: String,
    val period: String,
    val reasoningType: ReasoningType,
    val reasoningState: ReasoningState,
    val snapshotState: ReasoningSnapshotState,
    val createdByRule: String,
    val confidence: Float,
    val occurrenceCount: Int,
    val summary: String,
    val supportingMemoryIds: List<String>,
    val evidenceIds: List<String>,
    val isTruncated: Boolean,
    val reasoningVersion: String
)
