package com.amar.vault

import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalService

/**
 * Read-only projection of everything Amar Vault already knows about one entity/topic.
 *
 * It contains **no new knowledge, no new storage, and no new index**. It runs the *existing* hybrid
 * retrieval (BM25 + vector + substring + fuzzy + metadata boosts, via [RetrievalService]) with the
 * entity name as the query — exactly as the search/agentic paths already do — then buckets and
 * summarizes the returned [VaultItem]s. Ranking, OCR, metadata, and schema are untouched.
 */
data class EntityProfile(
    val name: String,
    /** From the canonical registry when the name resolves; null otherwise (most entities). */
    val entityType: String?,
    val referenceCount: Int,
    val documents: List<VaultItem>,
    val screenshots: List<VaultItem>,
    val images: List<VaultItem>,
    /** All references, newest first — the chronological/timeline projection of this entity. */
    val chronological: List<VaultItem>,
    val firstSeen: Long?,
    val lastSeen: Long?,
)

class EntityAggregator(private val retrievalService: RetrievalService) {

    private val docTypes = setOf("pdf", "word", "excel", "epub")

    suspend fun profile(name: String): EntityProfile {
        val trimmed = name.trim()

        // Same query pipeline the app already uses for search (QueryPlanner → RetrievalService).
        val plan = QueryPlanner.parse(trimmed)
        val items = retrievalService.retrieve(RetrievalRequest(plan.cleanedQuery, plan)).items

        // Entity type, only when the existing canonical registry recognizes the name.
        val resolution = CanonicalEntityRegistry.resolve(trimmed)
        val entityType = if (resolution.decision == CanonicalizationDecision.RESOLVED)
            resolution.canonicalId?.let { CanonicalEntityRegistry.entities[it]?.entityType }
        else null

        val documents = items.filter { it.itemType in docTypes }
        val screenshots = items.filter { it.itemType == "screenshot" }
        val images = items.filter { it.itemType !in docTypes && it.itemType != "screenshot" }
        val timestamps = items.map { it.timestamp }

        return EntityProfile(
            name = trimmed,
            entityType = entityType,
            referenceCount = items.size,
            documents = documents,
            screenshots = screenshots,
            images = images,
            chronological = items.sortedByDescending { it.timestamp },
            firstSeen = timestamps.minOrNull(),
            lastSeen = timestamps.maxOrNull(),
        )
    }
}
