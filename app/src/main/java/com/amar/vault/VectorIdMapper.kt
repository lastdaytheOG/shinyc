package com.amar.vault

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit ownership record for one indexed vector chunk (Sprint 3B, Task 2).
 *
 * Every vector in the native HNSW index is one chunk of a parent VaultItem. This
 * record makes that ownership explicit — parent resolution must never be inferred
 * from the chunk-id string. [parentId] equals [chunkId] for legacy single-vector
 * entries that were indexed under the bare item id.
 */
data class ChunkRecord(
    val chunkId: String,
    val parentId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
)

/**
 * Bidirectional mapper between hnswlib's integer IDs and vector chunk records.
 *
 * hnswlib only accepts size_t (integer) IDs. This class maintains a thread-safe
 * mapping so the rest of the app can work with string UUIDs as usual, and — since
 * Sprint 3B — carries each chunk's explicit [ChunkRecord] ownership metadata
 * (parentId / chunkIndex / totalChunks) instead of encoding ownership in the id
 * string.
 *
 * Thread safety: ConcurrentHashMap + AtomicInteger — lock-free for reads,
 * safe for concurrent writes.
 *
 * Persistence: exported/imported by [VectorSearchManager] to the mappings JSON
 * (schema v2 with an embedding-identity header; legacy v1 flat maps are adopted
 * on first load).
 */
class VectorIdMapper {

    private val counter = AtomicInteger(0)

    // Int -> ChunkRecord (C++ ID -> chunk ownership record)
    private val numericToRecord = ConcurrentHashMap<Int, ChunkRecord>()

    // String -> Int (chunk id -> C++ ID)
    private val stringToNumeric = ConcurrentHashMap<String, Int>()

    /**
     * Get or create a numeric ID for a chunk record.
     * If the chunk id has been seen before, returns the existing numeric ID.
     * Otherwise, assigns the next available integer and stores the record.
     */
    fun getOrCreateNumericId(record: ChunkRecord): Int {
        return stringToNumeric.computeIfAbsent(record.chunkId) { _ ->
            val newId = counter.getAndIncrement()
            numericToRecord[newId] = record
            newId
        }
    }

    /**
     * Look up the chunk id string for a C++ numeric ID.
     * Returns null if the ID is unknown (should not happen in normal operation).
     */
    fun getStringId(numericId: Int): String? {
        return numericToRecord[numericId]?.chunkId
    }

    /** The explicit ownership record for a C++ numeric ID, or null if it is unmapped. */
    fun getRecord(numericId: Int): ChunkRecord? = numericToRecord[numericId]

    /**
     * Look up the numeric ID for a chunk id string.
     * Returns null if the chunk id has never been mapped.
     */
    fun getNumericId(stringId: String): Int? {
        return stringToNumeric[stringId]
    }

    /**
     * Check if a chunk id has already been indexed.
     */
    fun contains(stringId: String): Boolean {
        return stringToNumeric.containsKey(stringId)
    }

    /**
     * Total number of mapped IDs.
     */
    val size: Int get() = numericToRecord.size

    /**
     * Clear all mappings (call alongside VectorEngine.init on full re-index).
     */
    fun clear() {
        numericToRecord.clear()
        stringToNumeric.clear()
        counter.set(0)
    }

    /**
     * Remove all mappings whose [ChunkRecord] satisfies [predicate].
     *
     * Used for delete propagation and orphan reconciliation, both of which now match
     * on the record's explicit [ChunkRecord.parentId] rather than parsing the id
     * string. Note this only detaches the id-mapping — the vector may still reside in
     * the native HNSW graph until a full rebuild, but it becomes unreachable (search
     * resolves numeric→string via this mapper and drops unmapped hits), which restores
     * correctness cheaply and without a native remove API. Idempotent: removing an
     * absent id is a no-op.
     *
     * @return the number of mappings removed.
     */
    fun removeMatching(predicate: (ChunkRecord) -> Boolean): Int {
        val toRemove = numericToRecord.entries.filter { predicate(it.value) }
        for (entry in toRemove) {
            stringToNumeric.remove(entry.value.chunkId)
            numericToRecord.remove(entry.key)
        }
        return toRemove.size
    }

    /**
     * Export all mappings (for persistence).
     */
    fun exportMappings(): Map<Int, ChunkRecord> {
        return HashMap(numericToRecord)
    }

    /**
     * Import mappings (restore from disk on boot).
     * Sets the counter to max(existingIds) + 1 to avoid collisions.
     */
    fun importMappings(mappings: Map<Int, ChunkRecord>) {
        clear()
        var maxId = -1
        for ((numId, record) in mappings) {
            numericToRecord[numId] = record
            stringToNumeric[record.chunkId] = numId
            if (numId > maxId) maxId = numId
        }
        counter.set(maxId + 1)
    }
}
