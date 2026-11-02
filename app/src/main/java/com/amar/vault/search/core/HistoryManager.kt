package com.amar.vault.search.core

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
