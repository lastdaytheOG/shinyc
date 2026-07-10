package com.amar.vault.collections.core

import com.amar.vault.search.core.SearchEngine
import com.amar.vault.search.models.SearchSession
import com.amar.vault.collections.models.CollectionRefreshPolicy

sealed class CollectionStrategy {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String?
    abstract val icon: String?
    abstract val refreshPolicy: CollectionRefreshPolicy
    
    // Executes the strategy against the search engine and returns matching Document IDs
    abstract suspend fun execute(): List<String>
}

class RuleBasedCollectionStrategy(
    override val id: String,
    override val title: String,
    override val subtitle: String? = null,
    override val icon: String? = null,
    override val refreshPolicy: CollectionRefreshPolicy = CollectionRefreshPolicy.INCREMENTAL,
    private val query: String = "",
    private val filters: List<String> = emptyList()
) : CollectionStrategy() {
    override suspend fun execute(): List<String> {
        // Build a SearchSession to leverage the existing SearchEngine rules
        val session = SearchSession(query = query, filters = filters)
        val resultSession = SearchEngine.search(session)
        return resultSession.results.map { it.document.id }
    }
}

class FutureAICollectionStrategy(
    override val id: String,
    override val title: String,
    override val subtitle: String? = "AI Generated",
    override val icon: String? = "✨",
    override val refreshPolicy: CollectionRefreshPolicy = CollectionRefreshPolicy.SCHEDULED
) : CollectionStrategy() {
    override suspend fun execute(): List<String> {
        // Placeholder for future vector/semantic AI grouping
        return emptyList()
    }
}

object CollectionRegistry {
    val defaultStrategies = listOf(
        RuleBasedCollectionStrategy(
            id = "sys_recent",
            title = "Recently Saved",
            icon = "🕒",
            refreshPolicy = CollectionRefreshPolicy.IMMEDIATE
            // In reality, SortEngine handles sorting by date, no specific query needed
        ),
        RuleBasedCollectionStrategy(
            id = "sys_favorites",
            title = "Favorites",
            icon = "❤️",
            refreshPolicy = CollectionRefreshPolicy.IMMEDIATE,
            filters = listOf("isFavorite:true") // Mocking filter syntax for the SearchEngine
        ),
        RuleBasedCollectionStrategy(
            id = "sys_images",
            title = "Images & Photos",
            icon = "🖼️",
            filters = listOf("type:PHOTO", "type:SCREENSHOT")
        ),
        RuleBasedCollectionStrategy(
            id = "sys_videos",
            title = "Videos",
            icon = "▶️",
            filters = listOf("type:VIDEO", "type:YOUTUBE_VIDEO", "type:INSTAGRAM_REEL", "type:TIKTOK")
        ),
        RuleBasedCollectionStrategy(
            id = "sys_recipes",
            title = "Recipes",
            icon = "🍳",
            query = "recipe cook bake" // Semantic/text fallback until AI categorization
        ),
        FutureAICollectionStrategy(id = "sys_ai_travel", title = "Travel Ideas"),
        FutureAICollectionStrategy(id = "sys_ai_coding", title = "Coding Resources")
    )
}
