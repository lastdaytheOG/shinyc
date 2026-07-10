package com.amar.vault.collections.core

import com.amar.vault.collections.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object CollectionRepository {
    private val _collections = MutableStateFlow<List<SmartCollection>>(emptyList())
    val collections: StateFlow<List<SmartCollection>> = _collections.asStateFlow()

    fun updateCollection(collection: SmartCollection) {
        val current = _collections.value.toMutableList()
        val index = current.indexOfFirst { it.id == collection.id }
        if (index != -1) {
            current[index] = collection
        } else {
            current.add(collection)
        }
        _collections.value = current
    }

    fun getCollection(id: String): SmartCollection? = _collections.value.find { it.id == id }
}

object CollectionEngine {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val collectionCache = ConcurrentHashMap<String, SmartCollection>()

    /**
     * Bootstraps the engine, loading all known strategies from the registry and processing them.
     */
    fun initialize() {
        CollectionRegistry.defaultStrategies.forEach { strategy ->
            scope.launch {
                generateCollection(strategy)
            }
        }
    }

    /**
     * Resolves a strategy into a tangible SmartCollection entity.
     */
    private suspend fun generateCollection(strategy: CollectionStrategy) {
        // Mark as Refreshing
        val initialStatus = getCachedOrEmpty(strategy).copy(lifecycle = CollectionLifecycle.REFRESHING)
        CollectionRepository.updateCollection(initialStatus)

        try {
            val documentIds = strategy.execute()
            
            // Generate dependencies asynchronously
            val covers = CollectionCoverGenerator.generateCovers(documentIds)
            val stats = CollectionStatisticsEngine.calculate(documentIds)

            val collection = SmartCollection(
                id = strategy.id,
                title = strategy.title,
                subtitle = strategy.subtitle,
                description = null,
                icon = strategy.icon,
                color = null,
                refreshPolicy = strategy.refreshPolicy,
                lifecycle = CollectionLifecycle.READY,
                documentIds = documentIds,
                statistics = stats,
                covers = covers
            )

            // Cache and push to repository
            collectionCache[strategy.id] = collection
            CollectionRepository.updateCollection(collection)

        } catch (e: Exception) {
            val errorState = getCachedOrEmpty(strategy).copy(lifecycle = CollectionLifecycle.ERROR)
            CollectionRepository.updateCollection(errorState)
        }
    }

    private fun getCachedOrEmpty(strategy: CollectionStrategy): SmartCollection {
        return collectionCache[strategy.id] ?: SmartCollection(
            id = strategy.id,
            title = strategy.title,
            subtitle = strategy.subtitle,
            description = null,
            icon = strategy.icon,
            color = null,
            refreshPolicy = strategy.refreshPolicy,
            lifecycle = CollectionLifecycle.CREATED,
            documentIds = emptyList(),
            statistics = CollectionStatistics(0, null, null, null, emptyMap()),
            covers = emptyList()
        )
    }

    /**
     * Invoked by CollectionEvents. Triggers partial or full refresh based on policies.
     */
    fun handleEvent(event: CollectionEvent) {
        scope.launch {
            CollectionRegistry.defaultStrategies.forEach { strategy ->
                // Basic implementation: if policy is IMMEDIATE, we recalculate.
                // In future, INCREMENTAL would just delta-add the item ID to the existing list instead of full execute().
                if (strategy.refreshPolicy == CollectionRefreshPolicy.IMMEDIATE || 
                    event is CollectionEvent.FullRefreshRequested) {
                    generateCollection(strategy)
                }
            }
        }
    }
}
