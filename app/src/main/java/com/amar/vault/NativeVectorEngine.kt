package com.amar.vault

/**
 * Kotlin interface to the native C++ HNSW Vector Engine.
 *
 * Lifecycle:
 *   1. Call [initEngine] at app startup — loads any saved index from disk.
 *   2. Call [addVector] whenever a new screenshot embedding is generated.
 *   3. Call [search] to find similar screenshots.
 *   4. Call [saveToDisk] in onStop() to persist the graph.
 *   5. Call [destroyEngine] in onDestroy() to free native memory.
 *
 * Thread safety:
 *   - addVector and search can be called concurrently from multiple threads.
 *   - saveToDisk acquires an exclusive lock and blocks all other operations.
 */
class NativeVectorEngine {

    companion object {
        init {
            System.loadLibrary("amar_vector_engine")
        }
    }

    /**
     * Initialize the HNSW index and load existing data from disk if available.
     * @param dim         Vector dimensionality (1024 for BGE-M3)
     * @param maxElements Maximum capacity of the index
     * @param storagePath Absolute path to context.filesDir for index persistence
     */
    external fun initEngine(dim: Int, maxElements: Int, storagePath: String)

    /**
     * Add a vector to the index. Thread-safe (concurrent calls OK).
     * @param numericId  Integer ID (map to VaultItem UUID via VectorIdMapper)
     * @param vector     1024-dimensional float array (MUST be L2-normalized for cosine sim)
     */
    external fun addVector(numericId: Int, vector: FloatArray)

    /**
     * Search for k nearest neighbors. Thread-safe (concurrent calls OK).
     * @param queryVector 1024-dimensional query vector (normalized)
     * @param k           Number of results to return
     * @return            IntArray of numeric IDs sorted by similarity (most similar first)
     */
    external fun search(queryVector: FloatArray, k: Int): IntArray

    /**
     * Search and return scores alongside IDs.
     * @param queryVector 1024-dimensional query vector (normalized)
     * @param k           Number of results to return
     * @return            FloatArray of scores paired with IntArray of IDs
     */
    external fun searchWithScores(queryVector: FloatArray, k: Int): FloatArray

    /**
     * Flush the HNSW graph to disk. MUST call in onStop().
     * Acquires exclusive lock — blocks addVector/search until complete.
     */
    external fun saveToDisk()

    /**
     * Free all native memory. MUST call in onDestroy().
     */
    external fun destroyEngine()

    /**
     * Get the current number of indexed vectors.
     */
    external fun getCount(): Int
}