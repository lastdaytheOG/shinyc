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
    private val symbols: List<String>,
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

    class Guess(val text: String, val chance: Double)

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
            val stepped = stepAll(beams, encOut)
            for ((b, s) in beams.zip(stepped)) {
                for (tok in topIndices(s.logp, width)) cand += Candidate(b.score + s.logp[tok], b.tokens + tok, s.h, s.ctx)
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
            Guess((1 until tokens.size - 1).joinToString("") { symbols[tokens[it] - FIRST_LETTER] }, exp(score))
        }

    private fun topIndices(values: FloatArray, k: Int): IntArray {
        val count = minOf(k, values.size)
        val taken = BooleanArray(values.size)
        return IntArray(count) {
            var best = -1
            for (v in values.indices) {
                if (!taken[v] && (best < 0 || values[v] > values[best])) best = v
            }
            taken[best] = true
            best
        }
    }

    private class Stepped(val logp: FloatArray, val h: FloatArray, val ctx: FloatArray)

    private fun stepAll(beams: List<Beam>, encOut: Array<FloatArray>): List<Stepped> =
        beams.map { beam ->
            val gi = decGates[beam.tokens.last()].copyOf()
            if (feed && cellCtxW != null) {
                val fed = matVec(cellCtxW, null, 3 * dec, 2 * enc, beam.ctx)
                for (i in gi.indices) gi[i] += fed[i]
            }
            val h = combine(gi, matVec(cellWhh, cellBhh, 3 * dec, dec, beam.h), beam.h, dec)
            val ctx = attend(matVec(attnW, null, 2 * enc, dec, h), encOut)
            val logits = matVec(outW, outB, vocab, dec + 2 * enc, h + ctx)
            var top = Float.NEGATIVE_INFINITY
            for (v in logits.indices) if (logits[v] > top) top = logits[v]
            var total = 0.0
            for (v in logits.indices) total += exp((logits[v] - top).toDouble())
            val norm = top + ln(total).toFloat()
            for (v in logits.indices) logits[v] -= norm
            Stepped(logits, h, ctx)
        }

    private fun matVec(w: FloatArray, bias: FloatArray?, rows: Int, cols: Int, x: FloatArray): FloatArray {
        val out = FloatArray(rows)
        var i = 0
        while (i + 4 <= rows) {
            var s0 = if (bias == null) 0f else bias[i]
            var s1 = if (bias == null) 0f else bias[i + 1]
            var s2 = if (bias == null) 0f else bias[i + 2]
            var s3 = if (bias == null) 0f else bias[i + 3]
            val r0 = i * cols
            val r1 = r0 + cols
            val r2 = r1 + cols
            val r3 = r2 + cols
            for (j in 0 until cols) {
                val xj = x[j]
                s0 += w[r0 + j] * xj
                s1 += w[r1 + j] * xj
                s2 += w[r2 + j] * xj
                s3 += w[r3 + j] * xj
            }
            out[i] = s0
            out[i + 1] = s1
            out[i + 2] = s2
            out[i + 3] = s3
            i += 4
        }
        while (i < rows) {
            var s = if (bias == null) 0f else bias[i]
            val r = i * cols
            for (j in 0 until cols) s += w[r + j] * x[j]
            out[i] = s
            i++
        }
        return out
    }

    private fun attend(q: FloatArray, encOut: Array<FloatArray>): FloatArray {
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
        return ctx
    }

    private fun gru(gi: FloatArray, h: FloatArray, whh: FloatArray, bhh: FloatArray, size: Int): FloatArray =
        combine(gi, matVec(whh, bhh, 3 * size, size, h), h, size)

    private fun combine(gi: FloatArray, gh: FloatArray, h: FloatArray, size: Int): FloatArray {
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
        private const val MAGIC = 0x484C5432
        private const val BOS = 1
        private const val EOS = 2
        private const val FIRST_LETTER = 3
        private const val MAX_IN = 24
        private const val MAX_OUT = 32

        fun read(input: InputStream): HinglishModel {
            val data = DataInputStream(input.buffered())
            val header = ByteArray(28)
            data.readFully(header)
            val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(hb.int == MAGIC) { "not a Hinglish model" }
            val emb = hb.int
            val enc = hb.int
            val dec = hb.int
            val inVocab = hb.int
            val vocab = hb.int
            val feed = hb.int == 1
            val symbols = List(vocab - FIRST_LETTER) {
                val lb = ByteArray(2)
                data.readFully(lb)
                val bytes = ByteArray(ByteBuffer.wrap(lb).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF)
                data.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            }
            fun tensor(n: Int): FloatArray {
                val bytes = ByteArray(2 * n)
                data.readFully(bytes)
                val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(n) { halfToFloat(b.short.toInt() and 0xFFFF) }
            }
            val srcEmb = tensor(inVocab * emb)
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
                Array(inVocab) { v -> project(encWih[d], encBih[d], srcEmb, v * emb, emb, 3 * enc, emb) }
            }
            val decGates = Array(vocab) { v -> project(cellWih, cellBih, tgtEmb, v * emb, emb, 3 * dec, inDim) }
            val cellCtxW = if (feed) FloatArray(3 * dec * 2 * enc) { i ->
                val row = i / (2 * enc)
                cellWih[row * inDim + emb + i % (2 * enc)]
            } else null
            return HinglishModel(enc, dec, vocab, symbols, feed, encGates, encWhh, encBhh, bridgeW, bridgeB, decGates,
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
    private val vault: KnownWords? = null,
    private val width: Int = 8,
    private val minChance: Double = 0.1,
    private val floor: Double = 0.01,
    private val ratio: Double = 0.5,
) : SpellingGuesser {

    private class Reading(val keys: List<String>, val hindi: String?)

    private val model by lazy { runCatching { load() }.getOrNull() }
    private val remembered = object : LinkedHashMap<String, Reading>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Reading>?) = size > 512
    }

    override fun keys(word: String): List<String> = read(word).keys

    override fun hindi(word: String): String? = read(word).hindi

    private fun read(word: String): Reading {
        synchronized(remembered) { remembered[word]?.let { return it } }
        val byKey = LinkedHashMap<String, Pair<String, Double>>()
        for (g in model?.guesses(word, width).orEmpty()) {
            val k = Hinglish.key(g.text)
            val kept = byKey[k]
            byKey[k] = (kept?.first ?: g.text) to ((kept?.second ?: 0.0) + g.chance)
        }
        val ranked = byKey.entries.sortedByDescending { it.value.second }
        val known = vault?.takeUnless { it.isEmpty }
        val inVault = if (known == null) emptyList() else ranked.filter { known.has(it.key) }
        val keys = if (known == null) {
            ranked.filter { it.value.second >= minChance }.map { it.key }
        } else {
            val best = inVault.firstOrNull()?.value?.second ?: 0.0
            inVault.filter { it.value.second >= maxOf(floor, ratio * best) }.map { it.key }
        }
        val top = ranked.firstOrNull()?.value
        val vaultTop = inVault.firstOrNull()?.value
        val hindi = when {
            top == null -> null
            vaultTop != null && vaultTop.second >= ratio * top.second -> vaultTop.first
            top.second >= minChance -> top.first
            else -> null
        }
        val reading = Reading(keys, hindi)
        synchronized(remembered) { remembered[word] = reading }
        return reading
    }
}
