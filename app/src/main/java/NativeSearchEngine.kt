package com.amar.vault

/**
 * Kotlin interface to the native C++ BM25 Search Engine.
 *
 * Lifecycle:
 *   1. Call [initEngine] at app startup or ViewModel init.
 *   2. Feed documents via [addDocument].
 *   3. Query via [search].
 *   4. Call [destroyEngine] in onDestroy() — MANDATORY to prevent C++ heap leaks.
 */
class NativeSearchEngine {
    companion object {
        init {
            System.loadLibrary("amar_search_engine")
        }
    }

    // --- Lifecycle ---

    /** Allocate the C++ engine on the native heap. Idempotent (safe to call twice). */
    external fun initEngine()

    /** Free the C++ engine. MUST be called in onDestroy(). */
    external fun destroyEngine()

    // --- Data Feeding ---

    /**
     * Index a document. If [docId] already exists, it will be re-indexed.
     * @param docId  Unique identifier (e.g., VaultItem.id).
     * @param text   Combined searchable text (OCR + tags + type).
     */
    external fun addDocument(docId: String, text: String)

    // --- Querying ---

    /**
     * Search for documents matching [query].
     * @return Array of docIds ranked by BM25 relevance (max 50 results).
     */
    external fun search(query: String): Array<String>

    // --- Maintenance ---

    /** Clear all indexed documents from memory. Engine remains alive. */
    external fun clear()
}
