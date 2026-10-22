package com.amar.vault.photos

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue

/**
 * The tokenizer of EmbeddingGemma 2's text model: byte-pair encoding with byte fallback, read
 * from the compact table `tools/visual-search/build_tokenizer.py` writes (`photos/gemma_bpe.bin`).
 *
 * It gives the same ids as the reference tokenizer, which the unit test checks line by line:
 * spaces become ▁ and the whole text is one word; every character starts as its own token, and a
 * character the vocabulary lacks starts as its UTF-8 bytes; then the adjacent pair with the lowest
 * merge rank is merged, the leftmost first among equals, until no pair can be merged. The result
 * is wrapped in `<bos>` … `<eos>`.
 */
class GemmaTokenizer private constructor(
    val bos: Int,
    val eos: Int,
    /** Code points of the single-character tokens, ascending, and their ids. */
    private val charCodePoints: IntArray,
    private val charIds: IntArray,
    /** The id of `<0x00>` … `<0xFF>`. */
    private val byteIds: IntArray,
    /** Each merge as `left shl 38 or right shl 20 or rank`, ascending: a pair is found by binary search. */
    private val merges: LongArray,
    /** The token a merge makes, by rank. */
    private val mergedIds: IntArray,
) {

    fun encode(text: String): IntArray {
        val word = text.replace(' ', SPACE)
        val c = ArrayList<Int>(word.length + 8)
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            val id = charId(cp)
            if (id >= 0) {
                c.add(id)
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) c.add(byteIds[b.toInt() and 0xFF])
            }
            i += Character.charCount(cp)
        }
        val n = c.size
        val ids = c.toIntArray()
        val next = IntArray(n) { if (it + 1 < n) it + 1 else -1 }
        val prev = IntArray(n) { it - 1 }
        val alive = BooleanArray(n) { true }
        // Lowest rank first, then the leftmost position: (rank shl 32) or position.
        val queue = PriorityQueue<Long>()
        for (p in 0 until n - 1) {
            val r = rankOf(ids[p], ids[p + 1])
            if (r >= 0) queue.add((r.toLong() shl 32) or p.toLong())
        }
        while (queue.isNotEmpty()) {
            val top = queue.poll()!!
            val rank = (top ushr 32).toInt()
            val pos = (top and 0xFFFFFFFFL).toInt()
            if (!alive[pos]) continue
            val right = next[pos]
            if (right < 0) continue
            // An entry for a pair that has since changed.
            if (rankOf(ids[pos], ids[right]) != rank) continue
            ids[pos] = mergedIds[rank]
            alive[right] = false
            val after = next[right]
            next[pos] = after
            if (after >= 0) prev[after] = pos
            val before = prev[pos]
            if (before >= 0) {
                val r = rankOf(ids[before], ids[pos])
                if (r >= 0) queue.add((r.toLong() shl 32) or before.toLong())
            }
            if (after >= 0) {
                val r = rankOf(ids[pos], ids[after])
                if (r >= 0) queue.add((r.toLong() shl 32) or pos.toLong())
            }
        }
        var count = 0
        for (p in 0 until n) if (alive[p]) count++
        val out = IntArray(count + 2)
        out[0] = bos
        var o = 1
        for (p in 0 until n) if (alive[p]) out[o++] = ids[p]
        out[o] = eos
        return out
    }

    private fun charId(codePoint: Int): Int {
        val at = charCodePoints.binarySearch(codePoint)
        return if (at >= 0) charIds[at] else -1
    }

    /** The merge rank of the pair, or -1 when the two never merge. */
    private fun rankOf(left: Int, right: Int): Int {
        val key = (left.toLong() shl 38) or (right.toLong() shl 20)
        var lo = 0
        var hi = merges.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val m = merges[mid] and KEY_MASK
            when {
                m < key -> lo = mid + 1
                m > key -> hi = mid - 1
                else -> return (merges[mid] and RANK_MASK).toInt()
            }
        }
        return -1
    }

    companion object {
        private const val SPACE = '▁'
        private const val RANK_MASK = (1L shl 20) - 1
        private const val KEY_MASK = RANK_MASK.inv()
        private const val ID_LIMIT = 1 shl 18

        /** Reads the table; [input] is closed by the caller. */
        fun read(input: InputStream): GemmaTokenizer {
            val bytes = DataInputStream(input).use { it.readBytes() }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == "GBPE") { "not a tokenizer table" }
            require(b.int == 1) { "unknown tokenizer table version" }
            val bos = b.int
            val eos = b.int
            b.int // unk: unused, every character falls back to bytes
            val nChars = b.int
            val cps = IntArray(nChars)
            val ids = IntArray(nChars)
            for (i in 0 until nChars) { cps[i] = b.int; ids[i] = b.int }
            val byteIds = IntArray(256) { b.int }
            val nMerges = b.int
            require(nMerges < (1 shl 20)) { "too many merges for the packed key" }
            val packed = LongArray(nMerges)
            val merged = IntArray(nMerges)
            for (rank in 0 until nMerges) {
                val left = b.int
                val right = b.int
                merged[rank] = b.int
                require(left < ID_LIMIT && right < ID_LIMIT) { "token id out of range" }
                packed[rank] = (left.toLong() shl 38) or (right.toLong() shl 20) or rank.toLong()
            }
            packed.sort()
            return GemmaTokenizer(bos, eos, cps, ids, byteIds, packed, merged)
        }
    }
}
