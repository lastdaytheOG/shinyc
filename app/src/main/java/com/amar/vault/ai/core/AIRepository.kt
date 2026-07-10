package com.amar.vault.ai.core

import com.amar.vault.ai.models.AIEnrichmentDocument
import com.amar.vault.ai.models.AIEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

object AIRepository {
    // In-memory cache representing the database table for AI enrichments
    private val store = ConcurrentHashMap<String, AIEnrichmentDocument>()

    fun save(document: AIEnrichmentDocument) {
        store[document.itemId] = document
        // Fire event that the item has been completely enriched
        AIEventBus.publish(AIEvent.EnrichmentCompleted(document))
    }

    fun get(itemId: String): AIEnrichmentDocument? = store[itemId]
    
    fun remove(itemId: String) {
        store.remove(itemId)
    }
}

object AIEventBus {
    private val _events = MutableSharedFlow<AIEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun publish(event: AIEvent) {
        _events.tryEmit(event)
    }
}
