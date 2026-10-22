package com.amar.vault.photos

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turning a unit vector into the bytes [PhotoVector] keeps, and back.
 *
 * One scale per vector, the largest magnitude over 127: the bytes use their whole range, and a
 * score computed on them differs from the float one in the third decimal at most, which never
 * reorders photos that differ by more than that.
 */
object PhotoVectors {

    fun quantize(vector: FloatArray): Pair<ByteArray, Float> {
        var max = 0f
        for (v in vector) max = maxOf(max, abs(v))
        if (max == 0f) return ByteArray(vector.size) to 0f
        val scale = max / 127f
        return ByteArray(vector.size) { (vector[it] / scale).roundToInt().coerceIn(-127, 127).toByte() } to scale
    }

    fun dequantize(bytes: ByteArray, scale: Float): FloatArray = FloatArray(bytes.size) { bytes[it] * scale }

    fun normalized(vector: FloatArray): FloatArray {
        var s = 0.0
        for (v in vector) s += v.toDouble() * v
        val n = sqrt(s).toFloat()
        return if (n > 0f) FloatArray(vector.size) { vector[it] / n } else vector
    }
}

/** A photo found by what it looks like, and how close it came to the query (cosine, -1…1). */
data class PhotoMatch(val itemId: String, val score: Float)

/**
 * Every picture's vector of one model, held in memory and compared with a query by an exact scan:
 * every picture is scored, so nothing that would have ranked is missed. At a phone's scale that
 * is cheaper than an approximate index — 10,000 pictures are 7.7 MB and one pass of dot products —
 * and it has no graph to rebuild, no deleted entries to sweep and no recall to tune.
 *
 * Thread-safe: writes and searches take the same lock; a search is a few milliseconds.
 */
class PhotoIndex(val dim: Int) {

    private val ids = ArrayList<String>()
    private val positionOf = HashMap<String, Int>()
    private var vectors = ByteArray(0)
    private var scales = FloatArray(0)

    val size: Int @Synchronized get() = ids.size

    @Synchronized
    fun contains(itemId: String): Boolean = itemId in positionOf

    /** The vector held for [itemId], as the scan reads it; null when it is not held. */
    @Synchronized
    fun vectorOf(itemId: String): FloatArray? {
        val at = positionOf[itemId] ?: return null
        val scale = scales[at]
        return FloatArray(dim) { vectors[at * dim + it] * scale }
    }

    /** Adds the picture's vector, or replaces the one it had. */
    @Synchronized
    fun put(itemId: String, bytes: ByteArray, scale: Float) {
        require(bytes.size == dim) { "a $dim-dimension index was given ${bytes.size} values" }
        val at = positionOf[itemId] ?: run {
            ensureCapacity(ids.size + 1)
            ids.add(itemId)
            (ids.size - 1).also { positionOf[itemId] = it }
        }
        System.arraycopy(bytes, 0, vectors, at * dim, dim)
        scales[at] = scale
    }

    /** Takes the picture out; the last one moves into its place. */
    @Synchronized
    fun remove(itemId: String): Boolean {
        val at = positionOf.remove(itemId) ?: return false
        val last = ids.size - 1
        if (at != last) {
            val moved = ids[last]
            ids[at] = moved
            positionOf[moved] = at
            System.arraycopy(vectors, last * dim, vectors, at * dim, dim)
            scales[at] = scales[last]
        }
        ids.removeAt(last)
        return true
    }

    /** Replaces everything held with [rows] (all of one model). */
    @Synchronized
    fun load(rows: List<PhotoVector>) {
        ids.clear(); positionOf.clear()
        vectors = ByteArray(0); scales = FloatArray(0)
        ensureCapacity(rows.size)
        for (row in rows) if (row.vector.size == dim) put(row.itemId, row.vector, row.scale)
    }

    /**
     * The [k] pictures most like [query] (a unit vector of this index's dimension), most alike
     * first; ties go to the picture added first. [only] narrows the pictures that may be listed.
     */
    @Synchronized
    fun top(query: FloatArray, k: Int, only: ((String) -> Boolean)? = null): List<PhotoMatch> {
        require(query.size == dim) { "a $dim-dimension index was given a ${query.size}-dimension query" }
        val n = ids.size
        if (n == 0 || k <= 0) return emptyList()
        val scores = FloatArray(n)
        PhotoScan.scores(query, vectors, scales, dim, n, scores)
        // Partial selection of the best k by a small min-heap of positions.
        val heap = IntArray(minOf(k, n))
        var size = 0
        fun worse(a: Int, b: Int) = scores[a] < scores[b] || (scores[a] == scores[b] && a > b)
        fun siftDown(start: Int) {
            var i = start
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var m = i
                if (l < size && worse(heap[l], heap[m])) m = l
                if (r < size && worse(heap[r], heap[m])) m = r
                if (m == i) return
                val t = heap[i]; heap[i] = heap[m]; heap[m] = t
                i = m
            }
        }
        for (p in 0 until n) {
            if (only != null && !only(ids[p])) continue
            if (size < heap.size) {
                heap[size] = p
                var i = size++
                while (i > 0) {
                    val parent = (i - 1) / 2
                    if (!worse(heap[i], heap[parent])) break
                    val t = heap[i]; heap[i] = heap[parent]; heap[parent] = t
                    i = parent
                }
            } else if (worse(heap[0], p)) {
                heap[0] = p
                siftDown(0)
            }
        }
        return (0 until size).map { heap[it] }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            .map { PhotoMatch(ids[it], scores[it]) }
    }

    private fun ensureCapacity(count: Int) {
        if (scales.size >= count) return
        val cap = maxOf(count, scales.size * 2, 64)
        vectors = vectors.copyOf(cap * dim)
        scales = scales.copyOf(cap)
    }
}

/**
 * The scan behind [PhotoIndex.top]: `out[i] = scales[i] * dot(query, vectors[i])`. In native code
 * where the library is there (a debug build runs Kotlin loops several times slower), in Kotlin
 * otherwise (unit tests on the JVM).
 */
internal object PhotoScan {

    private val native: Boolean = try {
        System.loadLibrary("amar_vector_engine")
        nativeCheck() == 1
    } catch (e: Throwable) {
        false
    }

    fun scores(query: FloatArray, vectors: ByteArray, scales: FloatArray, dim: Int, count: Int, out: FloatArray) {
        if (native) {
            nativeScores(query, vectors, scales, dim, count, out)
            return
        }
        for (i in 0 until count) {
            var s = 0f
            val base = i * dim
            for (j in 0 until dim) s += query[j] * vectors[base + j]
            out[i] = s * scales[i]
        }
    }

    @JvmStatic private external fun nativeCheck(): Int

    @JvmStatic private external fun nativeScores(
        query: FloatArray, vectors: ByteArray, scales: FloatArray, dim: Int, count: Int, out: FloatArray,
    )
}
