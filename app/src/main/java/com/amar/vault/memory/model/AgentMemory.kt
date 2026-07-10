package com.amar.vault.memory.model

data class AgentMemory(
    val memoryId: String,
    val memoryType: MemoryType,
    val memoryState: MemoryState,
    val confidence: Float,
    val firstSeen: Long,
    val lastSeen: Long,
    val occurrenceCount: Int,
    val supportingEventCount: Int,
    val supportingRelationshipCount: Int,
    val evidenceIds: List<String>,
    val summary: String,
    val memoryVersion: String
)
