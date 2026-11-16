package com.amar.vault.indexing.ppocr

import android.content.Context
import android.os.Build
import com.amar.vault.BuildConfig
import com.amar.vault.VaultLog
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePipeline
import com.amar.vault.indexing.LineReader
import com.amar.vault.indexing.PictureReader
import com.amar.vault.indexing.SheetLineReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PrefsChoice(context: Context, key: String) : RememberedChoice {
    private val prefs = context.applicationContext.getSharedPreferences("line_readers", Context.MODE_PRIVATE)
    private val key = "$key|${Build.HARDWARE}|${Build.MODEL}"

    override fun read(): String? = prefs.getString(key, null)

    override fun write(choice: String) {
        prefs.edit().putString(key, choice).apply()
    }
}

class LineReaders(
    private val files: LineModelFiles,
    private val hindi: PictureReader,
    private val choice: ((String) -> RememberedChoice)? = null,
    private val threads: Int = 4,
) : AutoCloseable {

    constructor(context: Context, hindi: PictureReader) :
        this(LineModelFiles(context), hindi, { key -> PrefsChoice(context, key) })

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
            val finders = files.fixedFinders()
            val readers = mapOf(
                LineModelFiles.ALL to files.fixedReaders(LineModelFiles.ALL),
                LineModelFiles.NUMBERS to files.fixedReaders(LineModelFiles.NUMBERS),
            )
            val chooser = choice
            val runners = if (chooser != null && (finders.isNotEmpty() || readers.values.any { it.isNotEmpty() })) {
                LiteRtChip.entries.map { LiteRtRunner(it, threads) }
            } else {
                emptyList()
            }
            sessions += runners

            val steadyFinder = PpOcrLineFinder(files.finder().path, manifest.finder, threads).also { sessions += it }
            val finder: LineFinder = if (chooser != null && runners.isNotEmpty() && finders.isNotEmpty()) {
                val quick = runners.associateTo(LinkedHashMap()) { runner ->
                    runner.chip.label to LiteRtLineFinder(runner, finders, manifest.finder)
                }
                FastestLineFinder(steadyFinder, quick, chooser(key(manifest, "finder", finders.map { it.path })))
            } else {
                steadyFinder
            }

            fun reader(role: String): LineReader {
                val letters = files.letters(role)
                val widths = files.widths(role)
                val steady = PpOcrLineReader(files.reader(role).path, letters, threads, widths).also { sessions += it }
                val models = readers.getValue(role)
                if (chooser == null || runners.isEmpty() || models.isEmpty()) return steady
                val quick = runners.associateTo(LinkedHashMap()) { runner ->
                    runner.chip.label to LiteRtLineReader(runner, models, letters, steady, widths)
                }
                return FastestLineReader(
                    "line reader $role", steady, quick, chooser(key(manifest, "reader-$role", models.map { it.path })),
                )
            }

            val all = reader(LineModelFiles.ALL)
            val numbers = reader(LineModelFiles.NUMBERS)
            return Opened(LinePipeline(finder, all, numbers, SheetLineReader(hindi)), sessions)
        } catch (e: Throwable) {
            sessions.asReversed().forEach { runCatching { it.close() } }
            throw e
        }
    }

    private fun key(manifest: LineModelManifest, part: String, paths: List<String>): String =
        "$part|${manifest.id}|${manifest.version}|" + paths.joinToString(",") { File(it).name }

    override fun close() {
        opened?.sessions?.asReversed()?.forEach { runCatching { it.close() } }
        opened = null
    }
}
