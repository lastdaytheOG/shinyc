package com.amar.vault.search.core

import com.amar.vault.ui.renderengine.models.RichSavedItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object SearchObserver {
    /**
     * Listens to the stream of fully built RichSavedItems.
     * When items change in the repository (e.g. metadata updated, favorite toggled),
     * this observer catches the new state, builds a document, and incrementally updates the index.
     */
    fun startObserving(coroutineScope: CoroutineScope, richItemsFlow: StateFlow<List<RichSavedItem>>) {
        coroutineScope.launch(Dispatchers.Default) {
            richItemsFlow.collect { items ->
                // In a production environment, we'd calculate a diff.
                // For now, we rebuild the in-memory index on major changes.
                val documents = items.mapNotNull { SearchDocumentBuilder.build(it) }
                SearchIndexManager.rebuildAll(documents)
            }
        }
    }
}
