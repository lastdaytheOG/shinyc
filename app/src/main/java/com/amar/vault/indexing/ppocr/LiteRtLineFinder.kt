package com.amar.vault.indexing.ppocr

import android.graphics.Bitmap
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePlace
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

class LiteRtLineFinder(
    private val modelPath: String,
    private val width: Int,
    private val height: Int,
    private val settings: FinderSettings = FinderSettings(),
    private val onGpu: Boolean = true,
) : SizedLineFinder, AutoCloseable {

    private class Opened(
        val environment: Environment,
        val model: CompiledModel,
        val inputs: List<TensorBuffer>,
        val outputs: List<TensorBuffer>,
    )

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "line-finder-litert") }
    private val thread = executor.asCoroutineDispatcher()
    private var opened: Opened? = null

    override fun fits(pictureWidth: Int, pictureHeight: Int): Boolean =
        LineFinding.size(pictureWidth, pictureHeight, settings.longSide) == (width to height)

    override suspend fun find(picture: Bitmap): List<LinePlace> = withContext(thread) {
        val prepared = LineFinding.input(
            pixelsOf(picture), picture.width, picture.height, settings.longSide, interleaved = true,
        )
        check(prepared.width == width && prepared.height == height) {
            "a ${picture.width} x ${picture.height} picture does not fit the $width x $height finder"
        }
        val model = opened ?: open().also { opened = it }
        model.inputs.single().writeFloat(prepared.pixels)
        model.model.run(model.inputs, model.outputs)
        val prob = model.outputs.single().readFloat()
        check(prob.size == width * height) { "the finder gave ${prob.size} values for $width x $height" }
        LineFinding.places(prob, width, height, picture.width, picture.height, settings)
    }

    private fun open(): Opened {
        val environment = Environment.create()
        try {
            val options = if (onGpu) {
                CompiledModel.Options(setOf(Accelerator.GPU)).apply {
                    gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
                }
            } else {
                CompiledModel.Options(setOf(Accelerator.CPU))
            }
            val model = CompiledModel.create(modelPath, options, environment)
            try {
                return Opened(environment, model, model.createInputBuffers(), model.createOutputBuffers())
            } catch (e: Throwable) {
                runCatching { model.close() }
                throw e
            }
        } catch (e: Throwable) {
            runCatching { environment.close() }
            throw e
        }
    }

    override fun close() {
        if (executor.isShutdown) return
        executor.execute {
            opened?.let { o ->
                (o.inputs + o.outputs).forEach { runCatching { it.close() } }
                runCatching { o.model.close() }
                runCatching { o.environment.close() }
            }
            opened = null
        }
        executor.shutdown()
    }
}
