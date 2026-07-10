package com.amar.vault.search.core

import com.amar.vault.search.models.SearchResult
import com.amar.vault.search.models.SearchSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SearchEngine {
    
    private val repository: SearchRepository = SearchIndexManager

    /**
     * The single entry point for all search queries.
     * Executes entirely in-memory over the cached Index via the Repository abstraction.
     */
    suspend fun search(session: SearchSession): SearchSession = withContext(Dispatchers.Default) {
        if (session.query.isBlank() && session.filters.isEmpty()) {
            return@withContext session.copy(results = emptyList())
        }

        val normalizedQuery = SearchNormalizer.normalize(session.query)
        val queryTokens = SearchTokenizer.tokenize(normalizedQuery)
        
        // 1. Retrieve all documents from Repository
        val allDocs = repository.getDocuments()
        
        // 2. Filter (Placeholders for Future FilterEngine)
        val filteredDocs = allDocs // e.g., FilterEngine.apply(allDocs, session.filters)
        
        // 3. Score & Match
        val scoredResults = filteredDocs.mapNotNull { doc ->
            val score = RankingPipeline.calculateFinalScore(doc, queryTokens)
            
            // If there's no query, just return everything (for pure filtering), otherwise enforce a > 0 score
            if (queryTokens.isEmpty() || score > 0) {
                SearchResult(
                    document = doc,
                    finalScore = score,
                    matchedFields = emptyList(), // Future: determine which fields matched
                    highlightedText = null // Future: generate snippets
                )
            } else {
                null
            }
        }
        
        // 4. Sort
        val finalSortedResults = scoredResults.sortedByDescending { it.finalScore }
        
        return@withContext session.copy(results = finalSortedResults)
    }
}
