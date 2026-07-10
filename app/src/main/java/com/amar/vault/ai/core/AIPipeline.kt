package com.amar.vault.ai.core

import com.amar.vault.ui.renderengine.models.RichSavedItem
import com.amar.vault.ai.models.AIEnrichmentDocument
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object AIValidator {
    private val allowedCategories = setOf(
        "technology", "programming", "fitness", "health", "recipes", 
        "travel", "finance", "shopping", "music", "education", "art", "science"
    )

    fun cleanTags(rawTags: String): List<String> {
        return rawTags.split(",")
            .map { it.trim().lowercase().replace(Regex("[^a-z0-9]"), "") }
            .filter { it.isNotBlank() && it.length > 2 }
            .distinct()
    }

    fun cleanCategory(rawCategory: String): String? {
        val cat = rawCategory.trim().lowercase().replace(Regex("[^a-z]"), "")
        return if (allowedCategories.contains(cat)) cat else null
    }

    fun validateSummary(rawSummary: String): String? {
        // Drop hallucinated non-answers
        if (rawSummary.contains("i cannot") || rawSummary.length < 10) return null
        return rawSummary.trim()
    }
}

object AIMerger {
    fun merge(itemId: String, results: List<ProcessorResult>): AIEnrichmentDocument {
        val tags = mutableListOf<String>()
        val categories = mutableListOf<String>()
        var shortSummary: String? = null
        val confidences = mutableMapOf<String, com.amar.vault.ai.models.AIConfidence>()

        results.forEach { result ->
            when (result.type) {
                "tags" -> {
                    val clean = AIValidator.cleanTags(result.rawValue)
                    if (clean.isNotEmpty() && result.confidence.score > 0.7) {
                        tags.addAll(clean)
                        confidences["tags"] = result.confidence
                    }
                }
                "category" -> {
                    val clean = AIValidator.cleanCategory(result.rawValue)
                    if (clean != null && result.confidence.score > 0.8) {
                        categories.add(clean)
                        confidences["category"] = result.confidence
                    }
                }
                "summary" -> {
                    val clean = AIValidator.validateSummary(result.rawValue)
                    if (clean != null && result.confidence.score > 0.6) {
                        shortSummary = clean
                        confidences["shortSummary"] = result.confidence
                    }
                }
            }
        }

        return AIEnrichmentDocument(
            id = "ai_$itemId",
            itemId = itemId,
            shortSummary = shortSummary,
            longSummary = null,
            tags = tags.distinct(),
            categories = categories.distinct(),
            entities = emptyMap(),
            topics = emptyList(),
            processingVersion = 1,
            promptVersion = "1.0",
            modelVersion = "mixed",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            confidences = confidences
        )
    }
}

object AIPipeline {
    private val processors = listOf(TagProcessor(), CategoryProcessor(), SummaryProcessor())

    /**
     * Executes all processors concurrently, validates the output, merges them, 
     * and persists the final AI document, firing events upon completion.
     */
    suspend fun execute(item: RichSavedItem) {
        AIEventBus.publish(com.amar.vault.ai.models.AIEvent.EnrichmentStarted(item.id))
        
        try {
            val results = coroutineScope {
                processors.map { processor ->
                    async { processor.process(item) }
                }.awaitAll()
            }
            
            val finalDocument = AIMerger.merge(item.id, results)
            
            // Persist to repository (which fires EnrichmentCompleted)
            AIRepository.save(finalDocument)
            
        } catch (e: Exception) {
            AIEventBus.publish(com.amar.vault.ai.models.AIEvent.EnrichmentFailed(item.id, e.message ?: "Unknown error"))
        }
    }
}
