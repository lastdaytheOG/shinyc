package com.amar.vault.photos

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * A typed query as a vector in EmbeddingGemma 2's space: the query, after the model's search
 * prompt, through its text model ([GemmaTokenizer] → ONNX → pooled `sentence_embedding`).
 *
 * The same text model reads English, Hindi and Hindi typed in Latin letters into one space, so
 * one photo index answers all three. The int8 build of the model gives the full-precision
 * vectors to four decimals (cosine 0.9999 on 15,703 XM3600 queries); trimmed to the vocabulary
 * of those three ways of writing, it gives the same vectors for them, and with 4-bit layers it
 * costs 1.2 points of Hindi recall (tools/visual-search/prune_query_model.py).
 *
 * Sessions are safe to run from several threads at once.
 */
class QueryEncoder(modelPath: String, private val tokenizer: GemmaTokenizer, threads: Int = 4) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })

    /** The image/video/audio feature inputs the model has (given empty for a text query), and their width. */
    private val featureInputs: Map<String, Long> = session.inputInfo
        .filterKeys { it.endsWith("_features") }
        .mapValues { (it.value.info as TensorInfo).shape.last() }

    /** The vector's width: as the model declares it, or measured when an export left it open. */
    val dim: Int = (session.outputInfo.getValue(OUTPUT).info as TensorInfo).shape.last().toInt()
        .takeIf { it > 0 } ?: encode("").size

    fun encode(query: String): FloatArray {
        val ids = tokenizer.encode(PROMPT + query)
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = ArrayList<OnnxTensor>()
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }), shape).also(tensors::add)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape).also(tensors::add)
            for ((name, width) in featureInputs) {
                inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(0, width)).also(tensors::add)
            }
            session.run(inputs, setOf(OUTPUT)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val v = (out[0].value as Array<FloatArray>)[0]
                return PhotoVectors.normalized(v)
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    companion object {
        /** EmbeddingGemma 2's prompt for a search query; photos are embedded without one. */
        const val PROMPT = "task: search result | query: "
        private const val OUTPUT = "sentence_embedding"
    }
}

/**
 * A picture as a vector in the same space as [QueryEncoder]'s queries. The model takes the picture
 * squashed to a square of [size] pixels, values scaled to -1…1, and returns a unit vector.
 */
class PhotoEncoder(modelPath: String, val size: Int, threads: Int = 4) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })

    /** The vector's width: as the model declares it, or measured on a blank picture when an export left it open. */
    val dim: Int = (session.outputInfo.values.first().info as TensorInfo).shape.last().toInt()
        .takeIf { it > 0 } ?: encode(FloatArray(3 * size * size)).size

    /** [pixels] as [PhotoPixels.of] makes them: 3 × size × size, planar. */
    fun encode(pixels: FloatArray): FloatArray {
        require(pixels.size == 3 * size * size) { "expected ${3 * size * size} values, got ${pixels.size}" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, size.toLong(), size.toLong())).use { input ->
            session.run(mapOf(INPUT to input)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val v = (out[0].value as Array<FloatArray>)[0]
                return PhotoVectors.normalized(v)
            }
        }
    }

    fun encode(bitmap: Bitmap): FloatArray = encode(PhotoPixels.of(bitmap, size))

    override fun close() = session.close()

    private companion object {
        const val INPUT = "pixels"
    }
}

/**
 * A picture as the photo model reads it: squashed (not cropped) to [size] × [size] the way it was
 * trained (PIL's antialiased bicubic resize), each channel scaled from 0…255 to -1…1, planar (all
 * red, then green, then blue).
 *
 * Android's own scaling (`Bitmap.createScaledBitmap`) samples without smoothing when it shrinks a
 * picture, and on the device the photo model's vector then drifted from the reference to cosine
 * 0.973 on one test picture; so the resize is done here, as PIL does it.
 */
object PhotoPixels {

    fun of(bitmap: Bitmap, size: Int): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        return resized(argb, w, h, size)
    }

    /**
     * [argb] (w × h) squashed to [size] × [size] as PIL's `resize((size, size), BICUBIC)` does it:
     * a horizontal pass then a vertical one, each rounded to 8 bits, then scaled to -1…1, planar.
     */
    fun resized(argb: IntArray, w: Int, h: Int, size: Int): FloatArray {
        require(argb.size == w * h)
        val xs = Taps(w, size)
        val ys = Taps(h, size)
        val mid = IntArray(h * size * 3)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until size) {
                val s = row + xs.start[x]
                val wt = xs.weights[x]
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in wt.indices) {
                    val c = argb[s + k]
                    r += wt[k] * ((c shr 16) and 0xFF)
                    g += wt[k] * ((c shr 8) and 0xFF)
                    b += wt[k] * (c and 0xFF)
                }
                val o = (y * size + x) * 3
                mid[o] = clip8(r)
                mid[o + 1] = clip8(g)
                mid[o + 2] = clip8(b)
            }
        }
        val plane = size * size
        val out = FloatArray(3 * plane)
        for (y in 0 until size) {
            val s = ys.start[y]
            val wt = ys.weights[y]
            for (x in 0 until size) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in wt.indices) {
                    val o = ((s + k) * size + x) * 3
                    r += wt[k] * mid[o]
                    g += wt[k] * mid[o + 1]
                    b += wt[k] * mid[o + 2]
                }
                val i = y * size + x
                out[i] = clip8(r) / 127.5f - 1f
                out[plane + i] = clip8(g) / 127.5f - 1f
                out[2 * plane + i] = clip8(b) / 127.5f - 1f
            }
        }
        return out
    }

    private fun clip8(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    /** PIL's bicubic taps (a = -0.5) for one axis, widened by the scale when shrinking. */
    private class Taps(inSize: Int, outSize: Int) {
        val start = IntArray(outSize)
        val weights: Array<FloatArray>

        init {
            val scale = inSize.toDouble() / outSize
            val filterScale = maxOf(scale, 1.0)
            val support = 2.0 * filterScale
            weights = Array(outSize) { i ->
                val center = (i + 0.5) * scale
                val lo = maxOf((center - support + 0.5).toInt(), 0)
                val hi = minOf((center + support + 0.5).toInt(), inSize)
                start[i] = lo
                val w = DoubleArray(hi - lo) { j -> cubic((j + lo - center + 0.5) / filterScale) }
                val sum = w.sum()
                FloatArray(w.size) { j -> (if (sum != 0.0) w[j] / sum else 0.0).toFloat() }
            }
        }

        private fun cubic(x: Double): Double {
            val a = -0.5
            val t = kotlin.math.abs(x)
            return when {
                t < 1.0 -> ((a + 2.0) * t - (a + 3.0)) * t * t + 1.0
                t < 2.0 -> (((t - 5.0) * t + 8.0) * t - 4.0) * a
                else -> 0.0
            }
        }
    }

    fun fromArgb(argb: IntArray, size: Int): FloatArray {
        val plane = size * size
        require(argb.size == plane)
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = argb[i]
            out[i] = ((c shr 16) and 0xFF) / 127.5f - 1f
            out[plane + i] = ((c shr 8) and 0xFF) / 127.5f - 1f
            out[2 * plane + i] = (c and 0xFF) / 127.5f - 1f
        }
        return out
    }
}
