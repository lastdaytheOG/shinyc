package com.amar.vault.retrieval

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

class HinglishModel private constructor(
    private val enc: Int,
    private val dec: Int,
    private val vocab: Int,
    private val feed: Boolean,
    private val encGates: Array<Array<FloatArray>>,
    private val encWhh: Array<FloatArray>,
    private val encBhh: Array<FloatArray>,
    private val bridgeW: FloatArray,
    private val bridgeB: FloatArray,
    private val decGates: Array<FloatArray>,
    private val cellCtxW: FloatArray?,
    private val cellWhh: FloatArray,
    private val cellBhh: FloatArray,
    private val attnW: FloatArray,
    private val outW: FloatArray,
    private val outB: FloatArray,
) {

    class Guess(val key: String, val chance: Double)

    private class Beam(val score: Double, val tokens: IntArray, val h: FloatArray, val ctx: FloatArray)

    private class Candidate(val score: Double, val tokens: IntArray, val h: FloatArray, val ctx: FloatArray)

    fun guesses(word: String, width: Int = 4): List<Guess> {
        if (word.isEmpty() || word.length > MAX_IN || word.any { it !in 'a'..'z' }) return emptyList()
        val src = IntArray(word.length) { word[it] - 'a' + FIRST_LETTER }
        val encOut = Array(src.size) { FloatArray(2 * enc) }
        var hf = FloatArray(enc)
        for (t in src.indices) {
            hf = gru(encGates[0][src[t]], hf, encWhh[0], encBhh[0], enc)
            hf.copyInto(encOut[t], 0)
        }
        var hb = FloatArray(enc)
        for (t in src.indices.reversed()) {
            hb = gru(encGates[1][src[t]], hb, encWhh[1], encBhh[1], enc)
            hb.copyInto(encOut[t], enc)
        }
        val h0 = FloatArray(dec)
        val joined = hf + hb
        for (i in 0 until dec) {
            var s = bridgeB[i]
            val row = i * 2 * enc
            for (j in 0 until 2 * enc) s += bridgeW[row + j] * joined[j]
            h0[i] = tanh(s)
        }
        var beams = listOf(Beam(0.0, intArrayOf(BOS), h0, FloatArray(2 * enc)))
        val done = ArrayList<Pair<Double, IntArray>>()
        repeat(MAX_OUT) {
            if (beams.isEmpty()) return@repeat
            val cand = ArrayList<Candidate>()
            for (b in beams) {
                val (logp, h, ctx) = step(b.tokens.last(), b.h, b.ctx, encOut)
                for (tok in topIndices(logp, width)) cand += Candidate(b.score + logp[tok], b.tokens + tok, h, ctx)
            }
            val sorted = cand.sortedByDescending { it.score }
            val next = ArrayList<Beam>()
            for (c in sorted) {
                val last = c.tokens.last()
                if (last == EOS) done += c.score to c.tokens
                else if (last >= FIRST_LETTER && next.size < width) next += Beam(c.score, c.tokens, c.h, c.ctx)
            }
            done.sortByDescending { it.first }
            while (done.size > width) done.removeAt(done.size - 1)
            if (done.size >= width && (next.isEmpty() || next[0].score < done.last().first)) return guessesOf(done)
            beams = next
        }
        return guessesOf(done)
    }

    private fun guessesOf(done: List<Pair<Double, IntArray>>): List<Guess> =
        done.filter { it.second.size > 2 }.map { (score, tokens) ->
            Guess(String(CharArray(tokens.size - 2) { 'a' + (tokens[it + 1] - FIRST_LETTER) }), exp(score))
        }

    private fun topIndices(values: FloatArray, k: Int): IntArray {
        val order = values.indices.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it })
        return order.take(k).toIntArray()
    }

    private fun step(token: Int, hPrev: FloatArray, ctxPrev: FloatArray, encOut: Array<FloatArray>): Triple<FloatArray, FloatArray, FloatArray> {
        val gi = decGates[token].copyOf()
        if (feed && cellCtxW != null) {
            val width = 2 * enc
            for (i in gi.indices) {
                var s = 0f
                val row = i * width
                for (j in 0 until width) s += cellCtxW[row + j] * ctxPrev[j]
                gi[i] += s
            }
        }
        val h = gru(gi, hPrev, cellWhh, cellBhh, dec)
        val q = FloatArray(2 * enc)
        for (i in q.indices) {
            var s = 0f
            val row = i * dec
            for (j in 0 until dec) s += attnW[row + j] * h[j]
            q[i] = s
        }
        val scores = FloatArray(encOut.size)
        var best = Float.NEGATIVE_INFINITY
        for (t in encOut.indices) {
            var s = 0f
            val e = encOut[t]
            for (j in q.indices) s += e[j] * q[j]
            scores[t] = s
            if (s > best) best = s
        }
        var sum = 0f
        for (t in scores.indices) {
            scores[t] = exp(scores[t] - best)
            sum += scores[t]
        }
        val ctx = FloatArray(2 * enc)
        for (t in encOut.indices) {
            val a = scores[t] / sum
            val e = encOut[t]
            for (j in ctx.indices) ctx[j] += a * e[j]
        }
        val logits = FloatArray(vocab)
        val width = dec + 2 * enc
        var top = Float.NEGATIVE_INFINITY
        for (v in 0 until vocab) {
            var s = outB[v]
            val row = v * width
            for (j in 0 until dec) s += outW[row + j] * h[j]
            for (j in 0 until 2 * enc) s += outW[row + dec + j] * ctx[j]
            logits[v] = s
            if (s > top) top = s
        }
        var total = 0.0
        for (v in logits.indices) total += exp((logits[v] - top).toDouble())
        val norm = top + ln(total).toFloat()
        for (v in logits.indices) logits[v] -= norm
        return Triple(logits, h, ctx)
    }

    private fun gru(gi: FloatArray, h: FloatArray, whh: FloatArray, bhh: FloatArray, size: Int): FloatArray {
        val gh = FloatArray(3 * size)
        for (i in 0 until 3 * size) {
            var s = bhh[i]
            val row = i * size
            for (j in 0 until size) s += whh[row + j] * h[j]
            gh[i] = s
        }
        val out = FloatArray(size)
        for (i in 0 until size) {
            val r = sigmoid(gi[i] + gh[i])
            val z = sigmoid(gi[size + i] + gh[size + i])
            val n = tanh(gi[2 * size + i] + r * gh[2 * size + i])
            out[i] = (1f - z) * n + z * h[i]
        }
        return out
    }

    private fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    companion object {
        private const val MAGIC = 0x484C5431
        private const val BOS = 1
        private const val EOS = 2
        private const val FIRST_LETTER = 3
        private const val MAX_IN = 24
        private const val MAX_OUT = 32

        fun read(input: InputStream): HinglishModel {
            val data = DataInputStream(input.buffered())
            val header = ByteArray(24)
            data.readFully(header)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(hb.int == MAGIC) { "not a Hinglish model" }
            val emb = hb.int
            val enc = hb.int
            val dec = hb.int
            val vocab = hb.int
            val feed = hb.int == 1
            fun tensor(n: Int): FloatArray {
                val bytes = ByteArray(2 * n)
                data.readFully(bytes)
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(n) { halfToFloat(b.short.toInt() and 0xFFFF) }
            }
            val srcEmb = tensor(vocab * emb)
            val encWih = Array(2) { FloatArray(0) }
            val encWhh = Array(2) { FloatArray(0) }
            val encBih = Array(2) { FloatArray(0) }
            val encBhh = Array(2) { FloatArray(0) }
            for (d in 0 until 2) {
                encWih[d] = tensor(3 * enc * emb)
                encWhh[d] = tensor(3 * enc * enc)
                encBih[d] = tensor(3 * enc)
                encBhh[d] = tensor(3 * enc)
            }
            val bridgeW = tensor(dec * 2 * enc)
            val bridgeB = tensor(dec)
            val tgtEmb = tensor(vocab * emb)
            val inDim = if (feed) emb + 2 * enc else emb
            val cellWih = tensor(3 * dec * inDim)
            val cellWhh = tensor(3 * dec * dec)
            val cellBih = tensor(3 * dec)
            val cellBhh = tensor(3 * dec)
            val attnW = tensor(2 * enc * dec)
            val outW = tensor(vocab * (dec + 2 * enc))
            val outB = tensor(vocab)
            val encGates = Array(2) { d ->
                Array(vocab) { v -> project(encWih[d], encBih[d], srcEmb, v * emb, emb, 3 * enc, emb) }
            }
            val decGates = Array(vocab) { v -> project(cellWih, cellBih, tgtEmb, v * emb, emb, 3 * dec, inDim) }
            val cellCtxW = if (feed) FloatArray(3 * dec * 2 * enc) { i ->
                val row = i / (2 * enc)
                cellWih[row * inDim + emb + i % (2 * enc)]
            } else null
            return HinglishModel(enc, dec, vocab, feed, encGates, encWhh, encBhh, bridgeW, bridgeB, decGates,
                cellCtxW, cellWhh, cellBhh, attnW, outW, outB)
        }

        private fun project(w: FloatArray, b: FloatArray, x: FloatArray, at: Int, n: Int, rows: Int, stride: Int): FloatArray =
            FloatArray(rows) { i ->
                var s = b[i]
                val row = i * stride
                for (j in 0 until n) s += w[row + j] * x[at + j]
                s
            }

        internal fun halfToFloat(h: Int): Float {
            val sign = (h ushr 15) and 1
            val exponent = (h ushr 10) and 0x1F
            val mantissa = h and 0x3FF
            val bits = when {
                exponent == 0 && mantissa == 0 -> sign shl 31
                exponent == 0 -> {
                    var m = mantissa
                    var e = -14
                    while (m and 0x400 == 0) {
                        m = m shl 1
                        e--
                    }
                    (sign shl 31) or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
                }
                exponent == 0x1F -> (sign shl 31) or (0xFF shl 23) or (mantissa shl 13)
                else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
            }
            return java.lang.Float.intBitsToFloat(bits)
        }
    }
}

class ModelSpellings(
    private val load: () -> HinglishModel?,
    private val width: Int = 4,
    private val minChance: Double = 0.1,
) : SpellingGuesser {

    private val model by lazy { runCatching { load() }.getOrNull() }
    private val remembered = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 512
    }

    override fun keys(word: String): List<String> {
        synchronized(remembered) { remembered[word]?.let { return it } }
        val found = model?.guesses(word, width)?.filter { it.chance >= minChance }?.map { it.key }.orEmpty()
        synchronized(remembered) { remembered[word] = found }
        return found
    }
}
