package com.amar.vault.indexing

import android.content.Context
import com.amar.vault.AppEmbeddingEngine
import com.amar.vault.IndexMetrics
import com.amar.vault.VaultLog
import com.amar.vault.VectorSearchManager
import com.amar.vault.retrieval.Bm25Index

/**
 * One chunk to be added to an index, carrying its explicit ownership record
 * (Sprint 3B, Task 2): every chunk states its parent item, its position, and the
 * sibling count. Consumers must use these fields — never parse ownership out of [id].
 * [parentId] defaults to [id] for single-chunk entries indexed under a bare item id.
 */
data class IndexEntry(
    val id: String,
    val text: String,
    val parentId: String = id,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
)

/**
 * Post-commit index update stage: given the persisted chunk entries, update a derived index.
 *
 * Interface justified (unlike `LanguageDetector`): the two implementations share an honest,
 * substitutable contract — "index these entries" — and are genuinely interchangeable under it
 * (each updates its own index the same way for the same input). This is real polymorphism,
 * not two divergent algorithms wearing a false-shared contract.
 *
 * Runs ONLY after the Room commit (commit-then-index); performs no Room writes. No indexing
 * algorithm changes — embedding, chunk ids, and BM25 text are all verbatim from the prior
 * inline code.
 *
 * @param onIndexed invoked with each entry's 0-based index after it is indexed (used by the
 *        document path to preserve its per-chunk progress emissions; ignored by the image path).
 */
interface IndexUpdater {
    suspend fun update(entries: List<IndexEntry>, onIndexed: suspend (Int) -> Unit = {})
}

/** Image path: embed each chunk and add it to the HNSW vector index, then persist mappings. */
class VectorIndexUpdater(
    private val context: Context,
    private val vectorSearch: VectorSearchManager,
) : IndexUpdater {

    override suspend fun update(entries: List<IndexEntry>, onIndexed: suspend (Int) -> Unit) {
        // No embedding model on this install: the item is already keyword-searchable (BM25
        // was updated before this step), so there is simply nothing to add to the vector index.
        if (!AppEmbeddingEngine.isAvailable(context)) return
        if (!vectorSearch.initialized) {
            VaultLog.w("IndexingPipeline", "VectorSearchManager not ready — initializing now")
            vectorSearch.initialize()
        }

        val embedStart = System.currentTimeMillis()
        entries.forEachIndexed { index, entry ->
            val vector = AppEmbeddingEngine.embedPassage(context, entry.text)
            val added = vectorSearch.indexVector(
                entry.id, vector, entry.parentId, entry.chunkIndex, entry.totalChunks
            )
            VaultLog.v("IndexTiming", "Chunk $index indexed: $added")
            onIndexed(index)
        }
        IndexMetrics.recordDuration(IndexMetrics.Timing.EMBED, System.currentTimeMillis() - embedStart)

        // Coalesced: one mappings write per cool-down window instead of a full JSON rewrite
        // per image. Batch flush points (worker saveState calls) remain immediate.
        vectorSearch.persistIdMappingsDeferred()
    }
}

/** Document path: add each chunk to the native BM25 index (no embedding). */
class Bm25IndexUpdater(private val bm25: Bm25Index) : IndexUpdater {

    override suspend fun update(entries: List<IndexEntry>, onIndexed: suspend (Int) -> Unit) {
        entries.forEachIndexed { index, entry ->
            bm25.addDocument(entry.id, entry.text)
            onIndexed(index)
        }
    }
}
