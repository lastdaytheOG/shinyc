package com.amar.vault.indexing.ppocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import com.amar.vault.indexing.FoundLine
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePlace
import com.amar.vault.indexing.LineReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

internal fun pixelsOf(picture: Bitmap): IntArray =
    IntArray(picture.width * picture.height).also {
        picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height)
    }

private fun session(env: OrtEnvironment, modelPath: String, threads: Int): OrtSession =
    env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })

class PpOcrLineFinder(
    modelPath: String,
    private val settings: FinderSettings = FinderSettings(),
    threads: Int = 4,
) : LineFinder, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session = session(env, modelPath, threads)
    private val input = session.inputNames.first()

    override suspend fun find(picture: Bitmap): List<LinePlace> = withContext(Dispatchers.Default) {
        find(pixelsOf(picture), picture.width, picture.height)
    }

    fun find(argb: IntArray, width: Int, height: Int): List<LinePlace> {
        val prepared = LineFinding.input(argb, width, height, settings.longSide)
        val shape = longArrayOf(1, 3, prepared.height.toLong(), prepared.width.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(prepared.pixels), shape).use { tensor ->
            session.run(mapOf(input to tensor)).use { out ->
                val map = out[0] as OnnxTensor
                val dims = map.info.shape
                val buffer = map.floatBuffer
                val prob = FloatArray(buffer.remaining())
                buffer.get(prob)
                return LineFinding.places(
                    prob, dims[dims.size - 1].toInt(), dims[dims.size - 2].toInt(), width, height, settings,
                )
            }
        }
    }

    override fun close() = session.close()
}

class PpOcrLineReader(
    modelPath: String,
    private val dictionary: List<String>,
    threads: Int = 4,
    private val widths: List<Int> = LineStrips.WIDTHS,
    private val batch: Int = 6,
) : LineReader, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session = session(env, modelPath, threads)
    private val input = session.inputNames.first()

    @Volatile
    private var letters: List<String>? = null

    override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> =
        withContext(Dispatchers.Default) {
            val texts = read(pixelsOf(picture), picture.width, picture.height, places)
            List(places.size) { FoundLine(texts[it].text, texts[it].confidence, places[it].box()) }
        }

    fun read(argb: IntArray, width: Int, height: Int, places: List<LinePlace>): List<ReadText> {
        val strips = places.map { Strip(it) }
        val texts = arrayOfNulls<ReadText>(places.size)
        val readable = strips.indices.filter { strips[it].readable }
        val groups = readable.groupBy { LineStrips.widthFor(strips[it], widths) }.toSortedMap()
        for ((batchWidth, lines) in groups) for (group in lines.chunked(batch)) {
            val one = 3 * LineStrips.HEIGHT * batchWidth
            val pixels = FloatArray(group.size * one)
            for ((n, index) in group.withIndex()) {
                LineStrips.fill(argb, width, height, strips[index], pixels, n * one, batchWidth)
            }
            val shape = longArrayOf(group.size.toLong(), 3, LineStrips.HEIGHT.toLong(), batchWidth.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), shape).use { tensor ->
                session.run(mapOf(input to tensor)).use { out ->
                    val result = out[0] as OnnxTensor
                    val dims = result.info.shape
                    val steps = dims[1].toInt()
                    val classes = dims[2].toInt()
                    val buffer = result.floatBuffer
                    val scores = FloatArray(buffer.remaining())
                    buffer.get(scores)
                    val known = letters ?: CtcText.letters(dictionary, classes).also { letters = it }
                    for ((n, index) in group.withIndex()) {
                        texts[index] = CtcText.read(scores, n * steps * classes, steps, classes, known)
                    }
                }
            }
        }
        return List(places.size) { texts[it] ?: ReadText("", 0f) }
    }

    override fun close() = session.close()
}
