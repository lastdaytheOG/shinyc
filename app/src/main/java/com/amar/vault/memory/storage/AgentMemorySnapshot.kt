package com.amar.vault.memory.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_memory_snapshot")
data class AgentMemorySnapshot(
    @PrimaryKey val memoryId: String,
    val memoryType: String,
    val memoryState: String,
    val confidence: Float,
    val occurrenceCount: Int,
    val supportingEventCount: Int,
    val supportingRelationshipCount: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val summary: String,
    val evidenceIdsJson: String,
    val memoryVersion: String,
    val memoryContextHash: String
)
