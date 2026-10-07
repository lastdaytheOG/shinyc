package com.amar.vault.retrieval

import android.content.Context
import androidx.collection.LruCache
import com.amar.vault.AppEmbeddingEngine
import com.amar.vault.VaultConfig
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import com.amar.vault.VectorSearchManager
import kotlinx.coroutines.flow.first

/**
 * Lexical (keyword) recall capability. Wraps the native BM25 engine, and — for the
 * candidate-bounded path only — the already-existing FTS4 index. Returns document ids in
 * rank order, bounded by the caller's budget. No ranking policy lives here.
 */
interface LexicalRetriever {
    /** Native BM25 candidate ids (already capped by the engine); bounded to [limit]. */
    fun bm25(query: String, limit: Int): List<String>

    /** FTS4 token-match candidate ids. Only used by the candidate-bounded path. */
    suspend fun fts(query: String, limit: Int): List<String>
}

class DefaultLexicalRetriever(
    private val db: VaultDatabase,
    private val bm25Index: Bm25Index,
) : LexicalRetriever {

    override fun bm25(query: String, limit: Int): List<String> =
        bm25Index.search(query).take(limit)

    override suspend fun fts(query: String, limit: Int): List<String> = try {
        // FTS4 MATCH; guarded because raw queries can contain characters FTS rejects.
        db.vaultDao().searchFts(query).first().map { it.id }.take(limit)
    } catch (e: Exception) {
        VaultLog.d("LexicalRetriever", "FTS skipped: ${e.message}")
        emptyList()
    }
}

/**
 * Semantic (embedding) recall capability. Owns the query-embedding cache and the HNSW
 * vector index. The service applies lane-eligibility rules (length/gibberish/ready); this
 * type only embeds and searches. Embedding semantics are preserved exactly: the vector
 * lane uses the raw (unprefixed) embedding, identical to the pre-Phase-1 code.
 */
/** One vector hit with its explicit ownership and its cosine similarity to the query. */
data class SemanticHit(val chunkId: String, val parentId: String, val similarity: Float)

interface SemanticRetriever {
    val isReady: Boolean
    /** False when the vector index holds nothing, so a vector search cannot return a hit. */
    val hasVectors: Boolean get() = true
    /** Cached query embedding (30 entries). [trueLength]: see [VaultConfig.Embedding.QUERY_TRUE_LENGTH]. */
    suspend fun embedQuery(
        query: String,
        trueLength: Boolean = VaultConfig.Embedding.QUERY_TRUE_LENGTH,
    ): FloatArray
    /** Uncached embedding for arbitrary text (late document embedding). */
    suspend fun embedText(text: String): FloatArray
    /** ANN search over a precomputed query vector. */
    fun searchVectors(vector: FloatArray, k: Int): List<String>
    /** The same search with ownership and similarity kept, most similar first. */
    fun searchHits(vector: FloatArray, k: Int): List<SemanticHit> = emptyList()
    /** Drop cached embeddings, so a benchmark can time each configuration from cold. */
    fun clearCaches() {}
}

class DefaultSemanticRetriever(
    private val context: Context,
    private val vectorSearchManager: VectorSearchManager,
) : SemanticRetriever {

    private val embeddingCache = object : LruCache<String, FloatArray>(30) {}

    // Passage-embedding LRU: the late-document-boost step re-embeds the same top document
    // chunks on every semantic-phrase query (they were previously uncached, costing one
    // full model inference per chunk per query). Content-keyed, so a re-indexed chunk with
    // different text can never hit a stale entry. Bounded: 32 × (1024-dim float vector
    // ≈ 4KB + key) ≈ 200KB. Populated only after a successful embed — a failed inference
    // throws before the put, so failures are never cached.
    private val passageCache = object : LruCache<String, FloatArray>(32) {}

    private val engine get() = AppEmbeddingEngine.get(context)

    override val isReady: Boolean
        get() = vectorSearchManager.initialized && AppEmbeddingEngine.isAvailable(context)

    override val hasVectors: Boolean get() = vectorSearchManager.getIndexedCount() > 0

    override suspend fun embedQuery(query: String, trueLength: Boolean): FloatArray {
        // The two forms are different vectors, so they must never share a cache entry.
        val key = if (trueLength) query else PADDED_KEY + query
        synchronized(embeddingCache) { embeddingCache.get(key) }?.let { return it }
        val vec = engine.embed(query, trueLength = trueLength)
        synchronized(embeddingCache) { embeddingCache.put(key, vec) }
        return vec
    }

    override suspend fun embedText(text: String): FloatArray {
        synchronized(passageCache) { passageCache.get(text) }?.let { return it }
        val vec = engine.embed(text)
        synchronized(passageCache) { passageCache.put(text, vec) }
        return vec
    }

    override fun searchVectors(vector: FloatArray, k: Int): List<String> =
        vectorSearchManager.search(vector, k)

    override fun searchHits(vector: FloatArray, k: Int): List<SemanticHit> =
        vectorSearchManager.searchHits(vector, k)
            .map { SemanticHit(it.record.chunkId, it.record.parentId, it.similarity) }

    override fun clearCaches() {
        synchronized(embeddingCache) { embeddingCache.evictAll() }
        synchronized(passageCache) { passageCache.evictAll() }
    }

    private companion object {
        /** Cache-key prefix for padded query embeddings; a line break cannot start a real query. */
        const val PADDED_KEY = "\npadded:"
    }
}
