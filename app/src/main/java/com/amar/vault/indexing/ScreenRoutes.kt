package com.amar.vault.indexing

import com.amar.vault.VaultLog
import com.amar.vault.indexing.ppocr.RememberedChoice
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger

object LineReadingControl {
    enum class Route { CHOSEN, LINES, WHOLE }

    @Volatile
    var route: Route = Route.CHOSEN
}

interface ScreenLines {
    suspend fun pipeline(): PictureReader?
    fun remembered(): RememberedChoice?
    fun release()
}

class ScreenRoutes(
    private val lines: ScreenLines,
    private val clock: () -> Long = System::nanoTime,
) {

    private val trial = Mutex()
    private val warmed = AtomicInteger()

    @Volatile
    private var memory: RememberedChoice? = null

    @Volatile
    private var choice: String? = null

    private var trials = 0
    private var linesTime = 0L
    private var wholeTime = 0L
    private var shared = 0
    private var wholeWords = 0

    val chosen: String? get() = choice

    suspend fun read(byLines: suspend (PictureReader) -> String?, whole: suspend () -> String): String {
        when (LineReadingControl.route) {
            LineReadingControl.Route.WHOLE -> return whole()
            LineReadingControl.Route.LINES -> return lines.pipeline()?.let { byLines(it) } ?: whole()
            LineReadingControl.Route.CHOSEN -> Unit
        }
        val kept = memory ?: lines.remembered()?.also { found ->
            choice = found.read()?.takeIf { it == LINES || it == WHOLE }
            memory = found
        } ?: return whole()
        if (choice == WHOLE) return whole()
        val reader = lines.pipeline() ?: return whole()
        if (choice == LINES) return byLines(reader) ?: whole()
        if (reader.trying && warmed.getAndIncrement() < WARM_AT_MOST) return byLines(reader) ?: whole()
        return trial.withLock {
            when (choice) {
                WHOLE -> whole()
                LINES -> byLines(reader) ?: whole()
                else -> tryBoth(kept, reader, byLines, whole)
            }
        }
    }

    private suspend fun tryBoth(
        kept: RememberedChoice,
        reader: PictureReader,
        byLines: suspend (PictureReader) -> String?,
        whole: suspend () -> String,
    ): String {
        if (trials == 0) whole()
        var linesText: String? = null
        var wholeText = ""
        val linesFirst = trials % 2 == 0
        for (step in 0 until 2) {
            val started = clock()
            if ((step == 0) == linesFirst) {
                linesText = byLines(reader)
                linesTime += clock() - started
            } else {
                wholeText = whole()
                wholeTime += clock() - started
            }
        }
        val theirs = words(wholeText)
        val mine = words(linesText.orEmpty())
        wholeWords += theirs.values.sum()
        shared += theirs.entries.sumOf { (word, n) -> minOf(n, mine[word] ?: 0) }
        trials++
        if (trials >= TRIALS) settle(kept)
        return linesText ?: wholeText
    }

    private fun settle(kept: RememberedChoice) {
        val to = when {
            shared < wholeWords * SHARED_AT_LEAST -> WHOLE
            linesTime > wholeTime * SLOWER_AT_MOST -> WHOLE
            else -> LINES
        }
        choice = to
        kept.write(to)
        VaultLog.i(
            "LineReaders",
            "screens: $to (by lines ${linesTime / 1_000_000} ms, whole ${wholeTime / 1_000_000} ms over $trials pictures, " +
                "$shared of $wholeWords words shared)",
        )
        if (to == WHOLE) lines.release()
    }

    companion object {
        const val LINES = "lines"
        const val WHOLE = "whole"
        const val TRIALS = 5
        const val WARM_AT_MOST = 8
        const val SLOWER_AT_MOST = 1.25
        const val SHARED_AT_LEAST = 0.25

        private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")

        fun words(text: String): Map<String, Int> =
            WORD.findAll(text.lowercase()).groupingBy { it.value }.eachCount()
    }
}
