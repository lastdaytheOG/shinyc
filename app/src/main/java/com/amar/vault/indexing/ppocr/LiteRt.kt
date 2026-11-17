package com.amar.vault.indexing.ppocr

import android.graphics.Bitmap
import com.amar.vault.indexing.FoundLine
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePlace
import com.amar.vault.indexing.LineReader
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

interface SizedLineFinder : LineFinder {
    fun fits(pictureWidth: Int, pictureHeight: Int): Boolean
}

enum class LiteRtChip(val label: String) {
    GPU("gpu"),
    GPU_HALF("gpu16"),
    PROCESSOR("litert"),
}

class LiteRtRunner(val chip: LiteRtChip, private val threads: Int = 4) : AutoCloseable {

    private class Opened(val model: CompiledModel, val inputs: List<TensorBuffer>, val outputs: List<TensorBuffer>)

    @Volatile
    private var owner: Thread? = null
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "litert-${chip.label}").also { owner = it }
    }
    private val thread = executor.asCoroutineDispatcher()
    private var environment: Environment? = null
    private val opened = HashMap<String, Opened>()

    suspend fun <T> on(work: LiteRtRunner.() -> T): T = withContext(thread) { work() }

    fun run(path: String, input: FloatArray): FloatArray {
        check(Thread.currentThread() === owner) { "a LiteRT model ran off its own thread" }
        val model = opened[path] ?: open(path).also { opened[path] = it }
        model.inputs.single().writeFloat(input)
        model.model.run(model.inputs, model.outputs)
        return model.outputs.single().readFloat()
    }

    private fun open(path: String): Opened {
        val env = environment ?: Environment.create().also { environment = it }
        val model = CompiledModel.create(path, options(), env)
        try {
            return Opened(model, model.createInputBuffers(), model.createOutputBuffers())
        } catch (e: Throwable) {
            runCatching { model.close() }
            throw e
        }
    }

    private fun options(): CompiledModel.Options = when (chip) {
        LiteRtChip.PROCESSOR -> CompiledModel.Options(setOf(Accelerator.CPU)).apply {
            cpuOptions = CompiledModel.CpuOptions(numThreads = threads)
        }
        LiteRtChip.GPU -> CompiledModel.Options(setOf(Accelerator.GPU)).apply {
            gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
        }
        LiteRtChip.GPU_HALF -> CompiledModel.Options(setOf(Accelerator.GPU)).apply {
            gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16)
        }
    }

    private fun release(model: Opened) {
        (model.inputs + model.outputs).forEach { runCatching { it.close() } }
        runCatching { model.model.close() }
    }

    fun forget(paths: Collection<String>) {
        runCatching {
            executor.execute { for (path in paths) opened.remove(path)?.let(::release) }
        }
    }

    override fun close() {
        if (executor.isShutdown) return
        runCatching {
            executor.execute {
                opened.values.forEach(::release)
                opened.clear()
                environment?.let { runCatching { it.close() } }
                environment = null
            }
        }
        executor.shutdown()
    }
}

class FinderModel(val path: String, val width: Int, val height: Int)

class LiteRtLineFinder(
    private val runner: LiteRtRunner,
    private val models: List<FinderModel>,
    private val settings: FinderSettings = FinderSettings(),
) : SizedLineFinder, AutoCloseable {

    private fun modelFor(pictureWidth: Int, pictureHeight: Int): FinderModel? {
        val (width, height) = LineFinding.size(pictureWidth, pictureHeight, settings.longSide)
        return models.firstOrNull { it.width == width && it.height == height }
    }

    override fun fits(pictureWidth: Int, pictureHeight: Int): Boolean = modelFor(pictureWidth, pictureHeight) != null

    override suspend fun find(picture: Bitmap): List<LinePlace> {
        val model = checkNotNull(modelFor(picture.width, picture.height)) {
            "no fixed finder takes a ${picture.width} x ${picture.height} picture"
        }
        return runner.on {
            val prepared = LineFinding.input(
                pixelsOf(picture), picture.width, picture.height, settings.longSide, interleaved = true,
            )
            val prob = run(model.path, prepared.pixels)
            check(prob.size == model.width * model.height) {
                "the finder gave ${prob.size} values for ${model.width} x ${model.height}"
            }
            LineFinding.places(prob, model.width, model.height, picture.width, picture.height, settings)
        }
    }

    override fun close() = runner.forget(models.map { it.path })
}

class ReaderModel(val path: String, val width: Int)

class LiteRtLineReader(
    private val runner: LiteRtRunner,
    private val models: List<ReaderModel>,
    private val dictionary: List<String>,
    private val wide: LineReader,
    private val widths: List<Int> = LineStrips.WIDTHS,
) : LineReader, AutoCloseable {

    private val byWidth = models.associateBy { it.width }

    @Volatile
    private var letters: List<String>? = null

    override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> {
        val strips = places.map { Strip(it) }
        val sizes = IntArray(places.size) { if (strips[it].readable) LineStrips.widthFor(strips[it], widths) else 0 }
        val mine = places.indices.filter { strips[it].readable && sizes[it] in byWidth }
        val others = places.indices.filter { strips[it].readable && sizes[it] !in byWidth }
        val out = arrayOfNulls<FoundLine>(places.size)
        if (mine.isNotEmpty()) {
            runner.on {
                val argb = pixelsOf(picture)
                for (i in mine) {
                    val width = sizes[i]
                    val pixels = FloatArray(3 * LineStrips.HEIGHT * width)
                    LineStrips.fill(argb, picture.width, picture.height, strips[i], pixels, 0, width, interleaved = true)
                    val read = decode(run(byWidth.getValue(width).path, pixels))
                    out[i] = FoundLine(read.text, read.confidence, places[i].box(), read.weakest)
                }
            }
        }
        if (others.isNotEmpty()) {
            val read = wide.read(picture, others.map { places[it] })
            for ((n, i) in others.withIndex()) out[i] = read[n]
        }
        return List(places.size) { out[it] ?: FoundLine("", 0f, places[it].box()) }
    }

    private fun decode(scores: FloatArray): ReadText {
        val classes = listOf(dictionary.size + 2, dictionary.size + 1).firstOrNull { scores.size % it == 0 }
            ?: throw IllegalStateException("the reader gave ${scores.size} values for ${dictionary.size} letters")
        val known = letters?.takeIf { it.size == classes } ?: CtcText.letters(dictionary, classes).also { letters = it }
        return CtcText.read(scores, 0, scores.size / classes, classes, known)
    }

    override fun close() = runner.forget(models.map { it.path })
}
