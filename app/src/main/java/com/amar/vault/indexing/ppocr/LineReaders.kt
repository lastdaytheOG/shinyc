package com.amar.vault.indexing.ppocr

import com.amar.vault.BuildConfig
import com.amar.vault.VaultLog
import com.amar.vault.indexing.LinePipeline
import com.amar.vault.indexing.PictureReader
import com.amar.vault.indexing.SheetLineReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LineReaders(
    private val files: LineModelFiles,
    private val hindi: PictureReader,
    private val threads: Int = 4,
) : AutoCloseable {

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
            val finder = PpOcrLineFinder(files.finder().path, manifest.finder, threads).also { sessions += it }
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
}
