package com.amar.vault.collections.core

import androidx.compose.ui.graphics.Color
import com.amar.vault.collections.models.CollectionPresentationModel
import com.amar.vault.collections.models.CollectionLifecycle
import com.amar.vault.collections.models.SmartCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object CollectionPresentationBuilder {
    fun build(collection: SmartCollection): CollectionPresentationModel {
        val countLabel = "${collection.statistics.itemCount} items"
        
        return CollectionPresentationModel(
            id = collection.id,
            title = collection.title,
            subtitle = collection.subtitle ?: countLabel,
            description = collection.description,
            icon = collection.icon,
            backgroundColor = collection.color ?: Color(0xFFF0F0F0),
            foregroundColor = Color(0xFF1C1C1E),
            coverImages = collection.covers,
            itemCountLabel = countLabel,
            isRefreshing = collection.lifecycle == CollectionLifecycle.REFRESHING,
            documentIds = collection.documentIds
        )
    }
}

object CollectionProvider {
    /**
     * Exposes a flow of purely presentation-ready collections for the UI.
     * Empty collections (itemCount == 0) are filtered out so they don't clutter the home screen.
     */
    fun observeCollections(): Flow<List<CollectionPresentationModel>> {
        return CollectionRepository.collections.map { collections ->
            collections
                .filter { it.statistics.itemCount > 0 } // Hide empty
                .map { CollectionPresentationBuilder.build(it) }
        }
    }
}
