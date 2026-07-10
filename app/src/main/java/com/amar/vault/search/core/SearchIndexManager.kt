package com.amar.vault.search.core

import com.amar.vault.search.models.SearchDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

interface SearchRepository {
    fun getDocuments(): List<SearchDocument>
    fun getDocumentCount(): Int
}

object SearchIndexManager : SearchRepository {
    private val index = ConcurrentHashMap<String, SearchDocument>()
    private val _isIndexing = MutableStateFlow(false)
    val isIndexing: StateFlow<Boolean> = _isIndexing.asStateFlow()

    override fun getDocuments(): List<SearchDocument> {
        return index.values.toList()
    }

    override fun getDocumentCount(): Int = index.size

    fun indexOne(document: SearchDocument) {
        index[document.id] = document
    }

    fun indexMany(documents: List<SearchDocument>) {
        documents.forEach { doc ->
            index[doc.id] = doc
        }
    }

    fun remove(documentId: String) {
        index.remove(documentId)
    }

    fun rebuildAll(documents: List<SearchDocument>) {
        _isIndexing.value = true
        index.clear()
        documents.forEach { doc ->
            index[doc.id] = doc
        }
        _isIndexing.value = false
    }
}
