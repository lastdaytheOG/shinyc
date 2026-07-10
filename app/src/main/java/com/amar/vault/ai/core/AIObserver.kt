package com.amar.vault.ai.core

import com.amar.vault.ui.renderengine.models.RichSavedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object AIObserver {
    /**
     * Listens to changes in the data layer (e.g. from the primary database or SearchIndex).
     * Decides if an item needs to be sent to the AI pipeline.
     */
    fun startObserving(coroutineScope: CoroutineScope, richItemsFlow: StateFlow<List<RichSavedItem>>) {
        coroutineScope.launch(Dispatchers.Default) {
            richItemsFlow.collect { items ->
                items.forEach { item ->
                    if (requiresEnrichment(item)) {
                        // Enqueue the item in a real queueing system (e.g., WorkManager)
                        // For the architecture scope, we directly invoke the pipeline to simulate the worker
                        launch {
                            AIPipeline.execute(item)
                        }
                    }
                }
            }
        }
    }

    private fun requiresEnrichment(item: RichSavedItem): Boolean {
        val existingEnrichment = AIRepository.get(item.id)
        if (existingEnrichment == null) return true // Never processed
        
        // Smart re-enrichment trigger:
        // E.g., If the user updated the note, we might want to re-run AI to extract new entities.
        // For now, we assume if it exists, we don't blindly re-run.
        val itemLastModified = item.rawStashItem.savedAt // Should ideally be updated_at
        if (itemLastModified > existingEnrichment.createdAt) {
            // Note/Title changed after AI ran
            return true 
        }

        return false
    }
}
