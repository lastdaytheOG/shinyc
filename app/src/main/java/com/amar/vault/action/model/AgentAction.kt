package com.amar.vault.action.model

data class AgentAction(
    val actionId: String,
    val entityId: String,
    val period: String,
    val actionType: AgentActionType,
    val confidence: Float,
    val summary: String,
    val supportingReasoningIds: List<String>,
    val evidenceIds: List<String>,
    val createdByRule: String,
    val isTruncated: Boolean,
    val occurrenceCount: Int,
    val generatedAt: Long,
    val lastEvaluatedAt: Long,
    val expiresAt: Long?,
    val actionVersion: String
)
