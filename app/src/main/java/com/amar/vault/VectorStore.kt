package com.amar.vault

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ln
import kotlin.random.Random

class VectorStore private constructor(private val context: Context) {

    companion object {
        private const val DIM             = EmbeddingEngine.EMBEDDING_DIM
        private const val VECTOR_FILE     = "vectors.bin"
        private const val ID_BYTES        = 36
        private const val BASE_ID_BYTES   = 36
        private const val FLOAT_BYTES     = DIM * 4
        private const val BINARY_BYTES    = DIM / 8
        private const val ENTRY_SIZE      = ID_BYTES + BASE_ID_BYTES + FLOAT_BYTES + BINARY_BYTES
        private const val M               = 16
        private const val EF_CONSTRUCTION = 200
        private const val EF_SEARCH       = 50
        private const val CHUNK_SEPARATOR = "_chunk"

        @Volatile
        private var INSTANCE: VectorStore? = null

        fun getInstance(context: Context): VectorStore {
            return INSTANCE ?: synchronized(this) {
                VectorStore(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private data class Node(
        val id: String,
        val baseId: String,
        val vector: FloatArray,
        val binaryVector: ByteArray,
        val level: Int,
        val connections: Array<MutableList<String>> = Array(level + 1) {
            CopyOnWriteArrayList<String>()
        }
    )

    private val nodes      = ConcurrentHashMap<String, Node>()
    private var entryPoint: String? = null
    private var maxLevel   = 0
    private val vectorFile = File(context.filesDir, VECTOR_FILE)

    init {
        loadAndRebuildGraph()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun insert(chunkId: String, vector: FloatArray, baseId: String) {
        synchronized(this) {
            if (nodes.containsKey(chunkId)) return
            val binary = toBinaryVector(vector)
            val level  = randomLevel()
            val node   = Node(chunkId, baseId, vector, binary, level)
            nodes[chunkId] = node
            appendToDisk(chunkId, baseId, vector, binary)
            insertIntoGraph(node)
        }
    }

    // Backward compat
    fun insert(id: String, vector: FloatArray) {
        insert("${id}${CHUNK_SEPARATOR}0", vector, id)
    }

    fun search(queryVector: FloatArray, k: Int = 50): List<String> {
        return searchWithScores(queryVector, k).map { it.first }
    }

    /**
     * Two-stage search — Qdrant method:
     * Stage 1: HNSW graph traversal using Hamming distance on binary vectors (POPCNT)
     * Ultra-fast — single CPU instruction per comparison
     * Stage 2: Re-rank top-100 candidates using full Float32 cosine similarity
     * Precise — loads actual float vectors only for finalists
     *
     * Returns base VaultItem IDs with max-pooled scores.
     * If multiple chunks of the same item match, highest score wins.
     */
    fun searchWithScores(queryVector: FloatArray, k: Int = 50): List<Pair<String, Float>> {
        if (nodes.isEmpty() || entryPoint == null) return emptyList()

        var ep: String
        var curLevel: Int
        synchronized(this) {
            ep       = entryPoint!!
            curLevel = maxLevel
        }

        // Quantize query to binary once — reused across all stage 1 comparisons
        val queryBinary = toBinaryVector(queryVector)

        // Stage 1: Binary graph traversal — fast Hamming distance
        while (curLevel > 0) {
            val candidates = searchLayerBinary(queryBinary, ep, 1, curLevel)
            ep = candidates.firstOrNull() ?: ep
            curLevel--
        }

        // Collect top-100 candidates via binary traversal at level 0
        val candidates = searchLayerBinary(
            queryBinary, ep, maxOf(EF_SEARCH, k * 2), 0
        ).take(100)

        // Stage 2: Precise Float32 cosine re-ranking of finalists
        val chunkScores = candidates.mapNotNull { chunkId ->
            val node = nodes[chunkId] ?: return@mapNotNull null
            node.baseId to cosineSimilarity(queryVector, node.vector)
        }

        // Max-pooling: group chunks by baseId, keep highest score per item
        val maxPooled = mutableMapOf<String, Float>()
        chunkScores.forEach { (baseId, score) ->
            val current = maxPooled[baseId] ?: 0f
            if (score > current) maxPooled[baseId] = score
        }

        return maxPooled.entries
            .sortedByDescending { it.value }
            .take(k)
            .map { it.key to it.value }
    }

    fun getVector(id: String): FloatArray? = nodes[id]?.vector
    fun size(): Int = nodes.size

    // ── HNSW Graph Logic ──────────────────────────────────────────────────────

    private fun insertIntoGraph(node: Node) {
        if (entryPoint == null) {
            entryPoint = node.id
            maxLevel   = node.level
            return
        }

        var ep       = entryPoint!!
        var curLevel = maxLevel

        while (curLevel > node.level) {
            val neighbors = searchLayer(node.vector, ep, 1, curLevel)
            ep = neighbors.firstOrNull() ?: ep
            curLevel--
        }

        for (lc in minOf(node.level, maxLevel) downTo 0) {
            val candidates = searchLayer(node.vector, ep, EF_CONSTRUCTION, lc)
            val selected   = selectNeighbors(node.vector, candidates, M)
            node.connections[lc].addAll(selected)

            for (neighborId in selected) {
                val neighbor = nodes[neighborId] ?: continue
                if (lc <= neighbor.level) {
                    neighbor.connections[lc].add(node.id)
                    if (neighbor.connections[lc].size > M * 2) {
                        val trimmed = selectNeighbors(
                            neighbor.vector, neighbor.connections[lc], M
                        )
                        neighbor.connections[lc] = CopyOnWriteArrayList(trimmed)
                    }
                }
            }
            ep = candidates.firstOrNull() ?: ep
        }

        if (node.level > maxLevel) {
            entryPoint = node.id
            maxLevel   = node.level
        }
    }

    /**
     * Stage 1: Binary HNSW traversal using Hamming distance.
     * Uses POPCNT hardware instruction via countOneBits() —
     * orders of magnitude faster than float dot products.
     * "Smaller is better" — 0 = perfect binary match.
     */
    private fun searchLayerBinary(
        queryBinary: ByteArray,
        entryId: String,
        ef: Int,
        level: Int
    ): List<String> {
        val visited   = mutableSetOf(entryId)
        val entryNode = nodes[entryId] ?: return emptyList()
        val entryDist = hammingDistance(queryBinary, entryNode.binaryVector).toFloat()

        val candidates = PriorityQueue<Pair<Float, String>>(compareBy { it.first })
        val results    = PriorityQueue<Pair<Float, String>>(compareByDescending { it.first })

        candidates.add(entryDist to entryId)
        results.add(entryDist to entryId)

        while (candidates.isNotEmpty()) {
            val (cDist, cId) = candidates.poll()
            val worstResult  = results.peek()?.first ?: Float.MAX_VALUE
            if (cDist > worstResult && results.size >= ef) break

            val node = nodes[cId] ?: continue
            if (level > node.level) continue

            for (nId in node.connections[level]) {
                if (!visited.add(nId)) continue
                val nNode = nodes[nId] ?: continue
                val nDist = hammingDistance(queryBinary, nNode.binaryVector).toFloat()
                val worst = results.peek()?.first ?: Float.MAX_VALUE
                if (results.size < ef || nDist < worst) {
                    candidates.add(nDist to nId)
                    results.add(nDist to nId)
                    if (results.size > ef) results.poll()
                }
            }
        }
        return results.map { it.second }.reversed()
    }

    /**
     * Float32 HNSW traversal — used during graph construction only.
     * Graph is built with float precision so edges are semantically accurate.
     */
    private fun searchLayer(
        query: FloatArray,
        entryId: String,
        ef: Int,
        level: Int
    ): List<String> {
        val visited   = mutableSetOf(entryId)
        val entryNode = nodes[entryId] ?: return emptyList()
        val entryDist = distance(query, entryNode.vector)

        val candidates = PriorityQueue<Pair<Float, String>>(compareBy { it.first })
        val results    = PriorityQueue<Pair<Float, String>>(compareByDescending { it.first })

        candidates.add(entryDist to entryId)
        results.add(entryDist to entryId)

        while (candidates.isNotEmpty()) {
            val (cDist, cId) = candidates.poll()
            val worstResult  = results.peek()?.first ?: Float.MAX_VALUE
            if (cDist > worstResult && results.size >= ef) break

            val node = nodes[cId] ?: continue
            if (level > node.level) continue

            for (nId in node.connections[level]) {
                if (!visited.add(nId)) continue
                val nNode = nodes[nId] ?: continue
                val nDist = distance(query, nNode.vector)
                val worst = results.peek()?.first ?: Float.MAX_VALUE
                if (results.size < ef || nDist < worst) {
                    candidates.add(nDist to nId)
                    results.add(nDist to nId)
                    if (results.size > ef) results.poll()
                }
            }
        }
        return results.map { it.second }.reversed()
    }

    private fun selectNeighbors(
        query: FloatArray,
        candidates: List<String>,
        m: Int
    ): List<String> {
        return candidates
            .mapNotNull { id -> nodes[id]?.vector?.let { id to distance(query, it) } }
            .sortedBy { it.second }
            .take(m)
            .map { it.first }
    }

    private fun randomLevel(): Int {
        var level = 0
        while (Random.nextDouble() < 1.0 / ln(M.toDouble()) && level < 16) level++
        return level
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) { dot += a[i] * b[i] }
        return maxOf(0f, 1f - dot)
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) { dot += a[i] * b[i] }
        return dot
    }

    /**
     * Hamming distance using POPCNT hardware instruction.
     * countOneBits() compiles to a single POPCNT CPU instruction on ARM64.
     * Smaller = more similar (0 = identical binary vectors).
     */
    private fun hammingDistance(a: ByteArray, b: ByteArray): Int {
        var distance = 0
        for (i in a.indices) {
            // ELITE FIX: 'and 0xFF' strips the sign-extension bits from Kotlin's toInt()
            distance += ((a[i].toInt() xor b[i].toInt()) and 0xFF).countOneBits()
        }
        return distance
    }

    private fun toBinaryVector(vector: FloatArray): ByteArray {
        val binary = ByteArray(BINARY_BYTES)
        for (i in vector.indices) {
            if (vector[i] > 0f) {
                binary[i / 8] = (binary[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
        }
        return binary
    }

    // ── Disk Persistence ──────────────────────────────────────────────────────

    private fun appendToDisk(
        chunkId: String,
        baseId: String,
        vector: FloatArray,
        binary: ByteArray
    ) {
        try {
            val entry = ByteArray(ENTRY_SIZE)
            chunkId.toByteArray().copyOf(ID_BYTES).copyInto(entry, 0)
            baseId.toByteArray().copyOf(BASE_ID_BYTES).copyInto(entry, ID_BYTES)
            var offset = ID_BYTES + BASE_ID_BYTES
            for (f in vector) {
                val bits = java.lang.Float.floatToIntBits(f)
                entry[offset]     = (bits and 0xFF).toByte()
                entry[offset + 1] = ((bits shr 8) and 0xFF).toByte()
                entry[offset + 2] = ((bits shr 16) and 0xFF).toByte()
                entry[offset + 3] = ((bits shr 24) and 0xFF).toByte()
                offset += 4
            }
            binary.copyInto(entry, offset)
            FileOutputStream(vectorFile, true).use { it.write(entry) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadAndRebuildGraph() {
        if (!vectorFile.exists() || vectorFile.length() == 0L) return
        val t0 = System.currentTimeMillis()
        try {
            RandomAccessFile(vectorFile, "r").use { raf ->
                val buf = raf.channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, vectorFile.length()
                ).apply { order(ByteOrder.LITTLE_ENDIAN) }

                val loadedNodes = mutableListOf<Node>()
                while (buf.remaining() >= ENTRY_SIZE) {
                    val chunkIdBytes = ByteArray(ID_BYTES)
                    buf.get(chunkIdBytes)
                    val chunkId = String(chunkIdBytes).trimEnd('\u0000')

                    val baseIdBytes = ByteArray(BASE_ID_BYTES)
                    buf.get(baseIdBytes)
                    val baseId = String(baseIdBytes).trimEnd('\u0000')

                    val vector = FloatArray(DIM) { buf.float }
                    val binary = ByteArray(BINARY_BYTES)
                    buf.get(binary)

                    val level = randomLevel()
                    val node  = Node(chunkId, baseId, vector, binary, level)
                    nodes[chunkId] = node
                    loadedNodes.add(node)
                }
                loadedNodes.forEach { insertIntoGraph(it) }
            }
            android.util.Log.d("VectorStore", "Loaded ${nodes.size} chunks in ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun close() {}
}