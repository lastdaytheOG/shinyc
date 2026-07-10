package com.amar.vault.search.core

import java.util.concurrent.ConcurrentHashMap

object SearchCache {
    // Thread-safe generic caches to prevent re-computation
    val documentCache = ConcurrentHashMap<String, com.amar.vault.search.models.SearchDocument>()
    val tokenizerCache = ConcurrentHashMap<String, Set<String>>()
    val suggestionCache = ConcurrentHashMap<String, List<String>>()
    val futureEmbeddingCache = ConcurrentHashMap<String, FloatArray>()
    
    fun clearAll() {
        documentCache.clear()
        tokenizerCache.clear()
        suggestionCache.clear()
        futureEmbeddingCache.clear()
    }
}

object HistoryManager {
    private val recentSearches = mutableListOf<String>()
    private const val MAX_HISTORY = 10
    
    fun addSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        
        recentSearches.remove(q)
        recentSearches.add(0, q)
        
        if (recentSearches.size > MAX_HISTORY) {
            recentSearches.removeLast()
        }
    }
    
    fun getRecentSearches(): List<String> = recentSearches.toList()
    
    fun removeSearch(query: String) {
        recentSearches.remove(query)
    }
    
    fun clearHistory() {
        recentSearches.clear()
    }
}

object SuggestionEngine {
    fun generateSuggestions(query: String, repository: SearchRepository): List<String> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return HistoryManager.getRecentSearches()
        
        // Cache hit
        SearchCache.suggestionCache[q]?.let { return it }
        
        val suggestions = mutableSetOf<String>()
        val docs = repository.getDocuments()
        
        // Very basic prefix matching for domains and tags
        docs.forEach { doc ->
            doc.domain?.let { if (it.lowercase().startsWith(q)) suggestions.add(it) }
            doc.tags.forEach { tag -> if (tag.lowercase().startsWith(q)) suggestions.add(tag) }
        }
        
        val result = suggestions.take(5).toList()
        SearchCache.suggestionCache[q] = result
        return result
    }
}
