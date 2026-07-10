package com.amar.vault.ai.models

data class AIConfidence(
    val score: Double,
    val processorId: String,
    val modelVersion: String,
    val source: String
)

data class AIEnrichmentDocument(
    val id: String,
    val itemId: String,
    val shortSummary: String?,
    val longSummary: String?,
    val tags: List<String>,
    val categories: List<String>,
    val entities: Map<String, List<String>>, // e.g. "People" -> ["Steve", "Amar"]
    val topics: List<String>,
    val processingVersion: Int,
    val promptVersion: String,
    val modelVersion: String,
    val createdAt: Long,
    val updatedAt: Long,
    val confidences: Map<String, AIConfidence> // Tracks confidence per field (e.g. "tags" -> AIConfidence)
)

sealed class AIEvent {
    data class EnrichmentStarted(val itemId: String) : AIEvent()
    data class EnrichmentCompleted(val document: AIEnrichmentDocument) : AIEvent()
    data class EnrichmentFailed(val itemId: String, val reason: String) : AIEvent()
}
