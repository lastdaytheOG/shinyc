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

interface ReadingLines {
    suspend fun pipeline(kind: String): PictureReader?
    fun remembered(kind: String): RememberedChoice?
    fun release()
}

class ReadingRoutes(
    private val lines: ReadingLines,
    private val clock: () -> Long = System::nanoTime,
) {

    private class Kind(val name: String) {
        val trial = Mutex()
        val warmed = AtomicInteger()

        @Volatile
        var memory: RememberedChoice? = null

        @Volatile
        var choice: String? = null

        var trials = 0
        var linesTime = 0L
        var wholeTime = 0L
        var shared = 0
        var wholeWords = 0
    }

    private val kinds = KINDS.associateWith { Kind(it) }

    fun chosen(kind: String): String? = kinds.getValue(kind).choice

    suspend fun read(kind: String, byLines: suspend (PictureReader) -> String?, whole: suspend () -> String): String {
        when (LineReadingControl.route) {
            LineReadingControl.Route.WHOLE -> return whole()
            LineReadingControl.Route.LINES -> return lines.pipeline(kind)?.let { byLines(it) } ?: whole()
            LineReadingControl.Route.CHOSEN -> Unit
        }
        val one = kinds.getValue(kind)
        val kept = one.memory ?: lines.remembered(kind)?.also { found ->
            one.choice = found.read()?.takeIf { it == LINES || it == WHOLE }
            one.memory = found
        } ?: return whole()
        if (one.choice == WHOLE) return whole()
        val reader = lines.pipeline(kind) ?: return whole()
        if (one.choice == LINES) return byLines(reader) ?: whole()
        if (reader.trying && one.warmed.getAndIncrement() < WARM_AT_MOST) return byLines(reader) ?: whole()
        return one.trial.withLock {
            when (one.choice) {
                WHOLE -> whole()
                LINES -> byLines(reader) ?: whole()
                else -> tryBoth(one, kept, reader, byLines, whole)
            }
        }
    }

    private suspend fun tryBoth(
        one: Kind,
        kept: RememberedChoice,
        reader: PictureReader,
        byLines: suspend (PictureReader) -> String?,
        whole: suspend () -> String,
    ): String {
        if (one.trials == 0) whole()
        var linesText: String? = null
        var wholeText = ""
        val linesFirst = one.trials % 2 == 0
        for (step in 0 until 2) {
            val started = clock()
            if ((step == 0) == linesFirst) {
                linesText = byLines(reader)
                one.linesTime += clock() - started
            } else {
                wholeText = whole()
                one.wholeTime += clock() - started
            }
        }
        val theirs = words(wholeText)
        val mine = words(linesText.orEmpty())
        one.wholeWords += theirs.values.sum()
        one.shared += theirs.entries.sumOf { (word, n) -> minOf(n, mine[word] ?: 0) }
        one.trials++
        if (one.trials >= TRIALS) settle(one, kept)
        return linesText ?: wholeText
    }

    private fun settle(one: Kind, kept: RememberedChoice) {
        val to = when {
            one.shared < one.wholeWords * SHARED_AT_LEAST -> WHOLE
            one.linesTime > one.wholeTime * SLOWER_AT_MOST -> WHOLE
            else -> LINES
        }
        one.choice = to
        kept.write(to)
        VaultLog.i(
            "LineReaders",
            "${one.name}: $to (by lines ${one.linesTime / 1_000_000} ms, whole ${one.wholeTime / 1_000_000} ms over " +
                "${one.trials} pictures, ${one.shared} of ${one.wholeWords} words shared)",
        )
        if (to == WHOLE && kinds.values.all { (it.choice ?: lines.remembered(it.name)?.read()) == WHOLE }) lines.release()
    }

    companion object {
        const val SCREENS = "screens"
        const val PAGES = "pages"
        val KINDS = listOf(SCREENS, PAGES)

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
