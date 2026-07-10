package com.amar.vault.action.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "agent_action_snapshot",
    indices = [
        Index(value = ["entityId", "actionType", "period"], unique = true)
    ]
)
data class AgentActionSnapshot(
    @PrimaryKey val actionId: String,

    val entityId: String,
    val period: String,

    val actionType: String,
    val actionState: String, // INTERNAL_CANDIDATE, CONFIRMED_ACTION, STALE, FAILED, ARCHIVED
    val userState: String,   // ACTIVE, DISMISSED, COMPLETED

    val confidence: Float,

    val summary: String,

    val supportingReasoningIdsJson: String,
    val evidenceIdsJson: String,

    val createdByRule: String,

    val occurrenceCount: Int,

    val isTruncated: Boolean,

    val lastTriggeredAt: Long?,
    val cooldownDays: Int,

    val generatedAt: Long,
    val lastEvaluatedAt: Long,
    val expiresAt: Long?,

    val actionVersion: String,
    val actionContextHash: String
)
