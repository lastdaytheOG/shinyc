package com.amar.vault

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One ANN hit: the explicit ownership record of the chunk and its cosine similarity to the query. */
data class VectorHit(val record: ChunkRecord, val similarity: Float)

class VectorSearchManager(private val context: Context) {

    companion object {
        // Dimension is shared with the embedder via VaultConfig so the two can never
        // silently disagree (a mismatch would drop every vector at index time).
        const val VECTOR_DIM       = VaultConfig.Embedding.DIM
        const val MAX_ELEMENTS     = VaultConfig.Vector.MAX_ELEMENTS
        const val DEFAULT_K        = VaultConfig.Vector.DEFAULT_K
        private const val MAPPINGS_FILENAME = VaultConfig.Vector.MAPPINGS_FILENAME

        @Volatile
        private var INSTANCE: VectorSearchManager? = null

        fun getInstance(context: Context): VectorSearchManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VectorSearchManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    private val engine   = NativeVectorEngine()
    private val idMapper = VectorIdMapper()
    private val initMutex = Mutex()

    // Coalesced mapping persistence: the per-item index path previously rewrote the FULL
    // mappings JSON after every image (O(mappings) bytes × N items per scan). Writes are now
    // coalesced to at most one per cool-down window; the explicit flush points (saveState /
    // removeItem / reconcile) still write immediately.
    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val persistPending = AtomicBoolean(false)
    private val persistWriteLock = Any()

    @Volatile
    var initialized = false
        private set

    suspend fun initialize() = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (initialized) return@withLock
            val storagePath = context.filesDir.absolutePath
            engine.initEngine(VECTOR_DIM, MAX_ELEMENTS, storagePath)
            // Task 1: record (or verify) which embedding model owns the persisted vectors.
            // Adoption/verification only — never blocks or alters retrieval.
            EmbeddingManifest.ensure(File(context.filesDir, VaultConfig.Vector.EMBEDDING_MANIFEST_FILENAME))
            restoreIdMappings()
            initialized = true
            android.util.Log.d("VectorSearch", "Initialized with ${engine.getCount()} vectors")
        }
    }

    /**
     * Index one chunk vector with its explicit ownership record (Task 2). [parentId]
     * defaults to [chunkId] for legacy single-vector callers that index an item under
     * its bare id.
     */
    fun indexVector(
        chunkId: String,
        embedding: FloatArray,
        parentId: String = chunkId,
        chunkIndex: Int = 0,
        totalChunks: Int = 1,
    ): Boolean {
        if (!initialized) {
            android.util.Log.e("VectorSearch", "Not initialized — skipping indexVector for $chunkId")
            return false
        }
        if (embedding.size != VECTOR_DIM) {
            android.util.Log.e("VectorSearch", "Wrong dim: ${embedding.size}")
            return false
        }
        if (idMapper.contains(chunkId)) return false

        val numericId = idMapper.getOrCreateNumericId(
            ChunkRecord(chunkId, parentId, chunkIndex, totalChunks)
        )
        engine.addVector(numericId, embedding)
        return true
    }

    fun indexBatch(items: List<Pair<String, FloatArray>>) {
        for ((id, embedding) in items) indexVector(id, embedding)
    }

    fun search(queryEmbedding: FloatArray, k: Int = DEFAULT_K): List<String> {
        if (!initialized) {
            android.util.Log.e("VectorSearch", "Not initialized — search returning empty")
            return emptyList()
        }
        if (queryEmbedding.size != VECTOR_DIM) return emptyList()
        val numericIds = engine.search(queryEmbedding, k)
        android.util.Log.d("VectorSearch", "Native search returned ${numericIds.size} results")
        return numericIds.toList().mapNotNull { idMapper.getStringId(it) }
    }

    /**
     * ANN search that keeps what [search] throws away: which item each chunk belongs to
     * ([ChunkRecord.parentId]) and how similar it is. Most similar first. Hits whose mapping
     * was detached (deleted items) are dropped, exactly as in [search].
     */
    fun searchHits(queryEmbedding: FloatArray, k: Int = DEFAULT_K): List<VectorHit> {
        if (!initialized || queryEmbedding.size != VECTOR_DIM) return emptyList()
        // Interleaved [id0, similarity0, id1, similarity1, ...]; ids are far below 2^24, so
        // the float carries them exactly.
        val flat = engine.searchWithScores(queryEmbedding, k)
        val hits = ArrayList<VectorHit>(flat.size / 2)
        var i = 0
        while (i + 1 < flat.size) {
            idMapper.getRecord(flat[i].toInt())?.let { hits.add(VectorHit(it, flat[i + 1])) }
            i += 2
        }
        return hits
    }

    suspend fun saveState() = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext
        engine.saveToDisk()
        persistIdMappings()
        android.util.Log.d("VectorSearch", "Saved state: ${engine.getCount()} vectors")
    }

    fun destroy() {
        if (!initialized) return
        engine.destroyEngine()
        initialized = false
    }

    fun getIndexedCount(): Int = if (initialized) engine.getCount() else 0
    fun isIndexed(id: String): Boolean = idMapper.contains(id)

    suspend fun reindex(items: List<Pair<String, FloatArray>>) = withContext(Dispatchers.IO) {
        engine.initEngine(VECTOR_DIM, MAX_ELEMENTS, context.filesDir.absolutePath)
        idMapper.clear()
        indexBatch(items)
        engine.saveToDisk()
        persistIdMappings()
    }

    /**
     * Delete-propagation: detach every vector belonging to [baseId], matched via each
     * chunk's explicit [ChunkRecord.parentId] (Task 2 — never parsed from the id string).
     * Persists the pruned mapping so the removal survives restart.
     * Idempotent — safe to call for an id with no vectors.
     */
    fun removeItem(baseId: String): Int {
        if (!initialized) return 0
        val removed = idMapper.removeMatching { it.chunkId == baseId || it.parentId == baseId }
        if (removed > 0) {
            persistIdMappings()
            IndexMetrics.increment(IndexMetrics.Event.DELETE_PROPAGATED)
            VaultLog.d("VectorSearch", "removeItem $baseId pruned $removed vector mapping(s)")
        }
        return removed
    }

    /**
     * Orphan reconciliation: detach any vector mapping whose owning item is no longer in
     * the source of truth. [validBaseIds] is the set of live VaultItem ids (typically the
     * set already loaded during BM25 hydration, so this adds no extra query). Cheap,
     * idempotent, and runs only when [IndexHealthState] says it's justified.
     *
     * @return number of orphan mappings pruned.
     */
    fun reconcile(validBaseIds: Set<String>): Int {
        if (!initialized) return 0
        val pruned = idMapper.removeMatching { record ->
            record.parentId !in validBaseIds
        }
        if (pruned > 0) {
            persistIdMappings()
            IndexMetrics.increment(IndexMetrics.Event.RECONCILE_ORPHANS_PRUNED, pruned.toLong())
            VaultLog.i("VectorSearch", "reconcile pruned $pruned orphan vector mapping(s)")
        }
        return pruned
    }

    private val mappingsFile: File get() = File(context.filesDir, MAPPINGS_FILENAME)

    /**
     * Mappings persistence — schema v2 (Sprint 3B):
     *
     * ```json
     * {
     *   "schema": 2,
     *   "embeddingModelId": "...", "embeddingModelVersion": "...", "embeddingSchemaVersion": 1,
     *   "mappings": { "<numericId>": {"id","parentId","chunkIndex","totalChunks"}, ... }
     * }
     * ```
     *
     * The identity header (Task 1) makes the file self-describing; the per-chunk record
     * (Task 2) carries explicit ownership. Legacy v1 files (flat numericId→chunkId map)
     * are adopted once at load by [restoreIdMappings] and rewritten as v2 on the next
     * persist.
     */
    fun persistIdMappings() = synchronized(persistWriteLock) {
        val mappings = idMapper.exportMappings()
        val entries  = JSONObject()
        for ((numId, record) in mappings) {
            entries.put(numId.toString(), JSONObject()
                .put("id", record.chunkId)
                .put("parentId", record.parentId)
                .put("chunkIndex", record.chunkIndex)
                .put("totalChunks", record.totalChunks))
        }
        val json = JSONObject()
            .put("schema", 2)
            .put("embeddingModelId", VaultConfig.Embedding.MODEL_ID)
            .put("embeddingModelVersion", VaultConfig.Embedding.MODEL_VERSION)
            .put("embeddingSchemaVersion", VaultConfig.Embedding.SCHEMA_VERSION)
            .put("mappings", entries)

        val tempFile = File(context.filesDir, "${MAPPINGS_FILENAME}.tmp")
        try {
            tempFile.writeText(json.toString())
            val target = mappingsFile
            if (target.exists()) target.delete()
            tempFile.renameTo(target)
        } catch (e: Exception) {
            tempFile.delete()
        }
    }

    /**
     * Coalesced persist for the high-frequency per-item indexing path. Guarantees a write
     * lands within one cool-down window of the last mapping change: [persistIdMappings]
     * exports live mapper state at write time, so every mapping added before the write is
     * included, and every later addition re-arms a new write. The crash window grows from
     * "since the last item" to "≤ one window of items" — both states heal identically via
     * the existing reconcile path (the native graph is only durable at saveState() anyway,
     * so an unpersisted mapping never outlives its vector's durability).
     */
    fun persistIdMappingsDeferred() {
        if (!persistPending.compareAndSet(false, true)) return
        persistScope.launch {
            delay(VaultConfig.Indexing.COOL_DOWN_MS)
            persistPending.set(false)
            persistIdMappings()
        }
    }

    private fun restoreIdMappings() {
        val file = mappingsFile
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            val mappings = if (json.has("schema")) readV2Mappings(json) else adoptV1Mappings(json)
            idMapper.importMappings(mappings)
        } catch (e: Exception) {
            idMapper.clear()
            file.delete()
        }
    }

    private fun readV2Mappings(json: JSONObject): Map<Int, ChunkRecord> {
        val entries  = json.getJSONObject("mappings")
        val mappings = mutableMapOf<Int, ChunkRecord>()
        val keys     = entries.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val e   = entries.getJSONObject(key)
            mappings[key.toInt()] = ChunkRecord(
                chunkId     = e.getString("id"),
                parentId    = e.getString("parentId"),
                chunkIndex  = e.getInt("chunkIndex"),
                totalChunks = e.getInt("totalChunks"),
            )
        }
        return mappings
    }

    /**
     * One-time adoption of a pre-3B v1 mappings file (flat numericId→chunkId map).
     * This is the LAST place ownership is ever derived from the historical
     * "${parentId}_chunkN" id convention — it runs once per install, at upgrade, to
     * make the implicit relationship explicit. All ownership decisions afterwards use
     * the stored [ChunkRecord]. The next persist rewrites the file as v2.
     */
    private fun adoptV1Mappings(json: JSONObject): Map<Int, ChunkRecord> {
        val raw  = mutableMapOf<Int, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            raw[key.toInt()] = json.getString(key)
        }
        // Same parent rule the v1 runtime used (substringBeforeLast): an id without
        // "_chunk" is its own parent.
        fun parentOf(id: String) = id.substringBeforeLast("_chunk")
        fun indexOf(id: String): Int =
            if (id.contains("_chunk")) id.substringAfterLast("_chunk").toIntOrNull() ?: 0 else 0

        val countByParent = raw.values.groupingBy { parentOf(it) }.eachCount()
        val adopted = raw.mapValues { (_, id) ->
            val parent = parentOf(id)
            ChunkRecord(
                chunkId     = id,
                parentId    = parent,
                chunkIndex  = indexOf(id),
                totalChunks = countByParent[parent] ?: 1,
            )
        }
        VaultLog.i("VectorSearch", "Adopted ${adopted.size} v1 vector mapping(s) into explicit chunk records")
        return adopted
    }
}