package com.amar.vault.collections.core

import com.amar.vault.search.core.SearchIndexManager
import com.amar.vault.collections.models.CollectionStatistics

object CollectionStatisticsEngine {
    fun calculate(documentIds: List<String>): CollectionStatistics {
        if (documentIds.isEmpty()) {
            return CollectionStatistics(0, null, null, 0L, emptyMap())
        }

        val docs = documentIds.mapNotNull { id -> 
            SearchIndexManager.getDocuments().find { it.id == id } 
        }

        if (docs.isEmpty()) {
            return CollectionStatistics(0, null, null, 0L, emptyMap())
        }

        val newest = docs.maxOfOrNull { it.createdAt }
        val oldest = docs.minOfOrNull { it.createdAt }
        
        // Count frequencies of platforms/content types for insights
        val platformBreakdown = mutableMapOf<String, Int>()
        docs.forEach { doc ->
            val key = doc.platform ?: doc.richItem.contentType.name
            platformBreakdown[key] = platformBreakdown.getOrDefault(key, 0) + 1
        }

        return CollectionStatistics(
            itemCount = docs.size,
            newestItemDate = newest,
            oldestItemDate = oldest,
            totalEstimatedSize = null, // Requires file size metadata not currently in SearchDocument
            platformBreakdown = platformBreakdown
        )
    }
}

object CollectionCoverGenerator {
    fun generateCovers(documentIds: List<String>): List<String> {
        val covers = mutableListOf<String>()
        val maxCovers = 4 // Up to 4 for a collage layout

        // We fetch the documents directly from the SearchIndex to resolve thumbnails
        val docs = documentIds.mapNotNull { id -> 
            SearchIndexManager.getDocuments().find { it.id == id } 
        }

        for (doc in docs) {
            if (covers.size >= maxCovers) break
            
            // Priority: Thumbnail > Fallback
            doc.richItem.thumbnail?.let { covers.add(it) }
        }

        return covers
    }
}
