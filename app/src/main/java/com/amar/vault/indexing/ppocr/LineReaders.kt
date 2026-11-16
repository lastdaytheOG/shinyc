package com.amar.vault.indexing.ppocr

import android.content.Context
import android.os.Build
import com.amar.vault.BuildConfig
import com.amar.vault.VaultLog
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePipeline
import com.amar.vault.indexing.PictureReader
import com.amar.vault.indexing.SheetLineReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PrefsFinderChoice(context: Context, manifestId: String) : FinderChoice {
    private val prefs = context.applicationContext.getSharedPreferences("line_readers", Context.MODE_PRIVATE)
    private val key = "finder|$manifestId|${Build.HARDWARE}|${Build.MODEL}"

    override fun read(): String? = prefs.getString(key, null)

    override fun write(choice: String) {
        prefs.edit().putString(key, choice).apply()
    }
}

class LineReaders(
    private val files: LineModelFiles,
    private val hindi: PictureReader,
    private val choice: ((String) -> FinderChoice)? = null,
    private val threads: Int = 4,
) : AutoCloseable {

    constructor(context: Context, hindi: PictureReader) :
        this(LineModelFiles(context), hindi, { id -> PrefsFinderChoice(context, id) })

    private class Opened(val pipeline: LinePipeline, val sessions: List<AutoCloseable>)

    private val gate = Mutex()

    @Volatile
    private var opened: Opened? = null

    @Volatile
    private var broken: String? = null

    suspend fun pipeline(): PictureReader? {
        opened?.let { return it.pipeline }
        if (!BuildConfig.PHOTO_ENGINE) return null
        return gate.withLock {
            opened?.let { return@withLock it.pipeline }
            if (!files.present()) return@withLock null
            val manifest = files.manifest() ?: return@withLock null
            if (broken == manifest.id) return@withLock null
            try {
                withContext(Dispatchers.IO) { open(manifest) }.also { opened = it }.pipeline
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                broken = manifest.id
                VaultLog.w("LineReaders", "the line readers could not be opened: ${e.message}")
                null
            }
        }
    }

    private fun open(manifest: LineModelManifest): Opened {
        val sessions = ArrayList<AutoCloseable>()
        try {
            val steady = PpOcrLineFinder(files.finder().path, manifest.finder, threads).also { sessions += it }
            val gpu = manifest.gpuFinder
            val gpuFile = files.gpuFinder()
            val finder: LineFinder = if (gpu != null && gpuFile != null && choice != null) {
                val onGpu = LiteRtLineFinder(gpuFile.path, gpu.width, gpu.height, manifest.finder, onGpu = true)
                val onProcessor = LiteRtLineFinder(gpuFile.path, gpu.width, gpu.height, manifest.finder, onGpu = false)
                sessions += onGpu
                sessions += onProcessor
                FastestLineFinder(steady, linkedMapOf(GPU to onGpu, LITERT to onProcessor), choice.invoke(manifest.id))
            } else {
                steady
            }
            val all = PpOcrLineReader(
                files.reader(LineModelFiles.ALL).path, files.letters(LineModelFiles.ALL), threads,
            ).also { sessions += it }
            val numbers = PpOcrLineReader(
                files.reader(LineModelFiles.NUMBERS).path, files.letters(LineModelFiles.NUMBERS), threads,
            ).also { sessions += it }
            return Opened(LinePipeline(finder, all, numbers, SheetLineReader(hindi)), sessions)
        } catch (e: Throwable) {
            sessions.forEach { runCatching { it.close() } }
            throw e
        }
    }

    override fun close() {
        opened?.sessions?.forEach { runCatching { it.close() } }
        opened = null
    }

    companion object {
        const val GPU = "gpu"
        const val LITERT = "litert"
    }
}
