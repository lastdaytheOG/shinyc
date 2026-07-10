package com.amar.vault.collections.models

import androidx.compose.ui.graphics.Color

enum class CollectionRefreshPolicy {
    IMMEDIATE,
    INCREMENTAL,
    LAZY,
    SCHEDULED
}

enum class CollectionLifecycle {
    CREATED,
    REFRESH_NEEDED,
    REFRESHING,
    READY,
    ERROR
}

data class SmartCollection(
    val id: String,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val icon: String?,
    val color: Color?,
    val refreshPolicy: CollectionRefreshPolicy,
    val lifecycle: CollectionLifecycle,
    val documentIds: List<String>,
    val parentId: String? = null,
    val childrenIds: List<String> = emptyList(),
    val statistics: CollectionStatistics,
    val covers: List<String>
)

data class CollectionStatistics(
    val itemCount: Int,
    val newestItemDate: Long?,
    val oldestItemDate: Long?,
    val totalEstimatedSize: Long?,
    val platformBreakdown: Map<String, Int>
)

data class CollectionPresentationModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String?,
    val icon: String?,
    val backgroundColor: Color,
    val foregroundColor: Color,
    val coverImages: List<String>, // Up to 4 for a collage
    val itemCountLabel: String,
    val isRefreshing: Boolean,
    val documentIds: List<String>
)

sealed class CollectionEvent {
    data class ItemSaved(val documentId: String) : CollectionEvent()
    data class ItemDeleted(val documentId: String) : CollectionEvent()
    data class MetadataUpdated(val documentId: String) : CollectionEvent()
    data class TagsChanged(val documentId: String) : CollectionEvent()
    data class FavoriteChanged(val documentId: String, val isFavorite: Boolean) : CollectionEvent()
    object FullRefreshRequested : CollectionEvent()
}
