package com.amar.vault.search.models

import com.amar.vault.ui.renderengine.models.RichSavedItem

data class SearchResult(
    val document: SearchDocument,
    val finalScore: Double,
    val matchedFields: List<String> = emptyList(),
    val highlightedText: String? = null,
)

data class SearchSession(
    val query: String = "",
    val filters: List<String> = emptyList(),
    val results: List<SearchResult> = emptyList(),
)

// NOTE (Sprint 3B, Task 4): SearchResult and SearchSession were removed together with
// the legacy in-memory SearchEngine/RankingPipeline. SearchDocument remains because the
// live suggestion path (SearchViewModel → SuggestionEngine → SearchIndexManager) is
// typed against it. Production retrieval is com.amar.vault.retrieval (RetrievalService).

data class SearchDocument(
    val id: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val domain: String?,
    val platform: String?,
    val creator: String?,
    val folder: String?,
    val tags: List<String>,
    val notes: String?,
    val filename: String?,
    val createdAt: Long,
    val updatedAt: Long,

    // Extracted searchable tokens
    val tokens: Set<String>,

    // Future placeholders
    val futureOCRText: String? = null,
    val futureAISummary: String? = null,
    val futureAITags: List<String> = emptyList(),

    // Display fields
    val richItem: RichSavedItem
)
