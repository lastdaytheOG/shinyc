package com.amar.vault.indexing.ppocr

import android.graphics.Bitmap
import com.amar.vault.VaultLog
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.max

interface FinderChoice {
    fun read(): String?
    fun write(choice: String)
}

class FastestLineFinder(
    private val steady: LineFinder,
    private val quick: Map<String, SizedLineFinder>,
    private val remembered: FinderChoice,
    private val clock: () -> Long = System::nanoTime,
) : LineFinder {

    private val trial = Mutex()

    @Volatile
    private var choice: String? = remembered.read()?.takeIf { it == STEADY || it in quick }

    private val out = HashSet<String>()
    private val times = HashMap<String, Long>()
    private var steadyTime = 0L
    private var trials = 0

    val chosen: String? get() = choice

    override suspend fun find(picture: Bitmap): List<LinePlace> {
        if (choice == STEADY || quick.values.none { it.fits(picture.width, picture.height) }) return steady.find(picture)
        if (choice != null) return settled(picture)
        return trial.withLock {
            if (choice != null) settled(picture) else tryAll(picture)
        }
    }

    private suspend fun settled(picture: Bitmap): List<LinePlace> {
        val finder = quick[choice]?.takeIf { it.fits(picture.width, picture.height) } ?: return steady.find(picture)
        return try {
            finder.find(picture)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            settle(STEADY, "the $choice finder failed: ${e.message}")
            steady.find(picture)
        }
    }

    private suspend fun tryAll(picture: Bitmap): List<LinePlace> {
        steady.find(picture)
        val started = clock()
        val places = steady.find(picture)
        steadyTime += clock() - started
        for ((name, finder) in quick) {
            if (name in out || !finder.fits(picture.width, picture.height)) continue
            try {
                finder.find(picture)
                val from = clock()
                val found = finder.find(picture)
                val took = clock() - from
                if (same(places, found)) {
                    times[name] = (times[name] ?: 0L) + took
                } else {
                    drop(name, "it found other lines: ${found.size} against ${places.size}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                drop(name, "it failed: ${e.message}")
            }
        }
        trials++
        val running = quick.keys.filter { it !in out && it in times }
        if (running.isEmpty()) {
            settle(STEADY, "no quicker finder can be used")
        } else if (trials >= TRIALS) {
            val best = running.minByOrNull { times.getValue(it) }!!
            val summary = running.joinToString { "$it ${times.getValue(it) / 1_000_000} ms" } +
                ", $STEADY ${steadyTime / 1_000_000} ms over $trials pictures"
            settle(if (times.getValue(best) < steadyTime * NEEDED) best else STEADY, summary)
        }
        return places
    }

    private fun drop(name: String, why: String) {
        out += name
        VaultLog.i("LineReaders", "line finder $name is out: $why")
    }

    private fun settle(to: String, why: String) {
        choice = to
        remembered.write(to)
        VaultLog.i("LineReaders", "line finder: $to ($why)")
        for ((name, finder) in quick) {
            if (name != to && finder is AutoCloseable) runCatching { finder.close() }
        }
    }

    companion object {
        const val STEADY = "steady"
        const val TRIALS = 3
        const val NEEDED = 0.85
        private const val NEAR = 6f
        private const val AGREE = 0.95

        fun same(a: List<LinePlace>, b: List<LinePlace>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty()
            if (abs(a.size - b.size) > max(1, a.size / 20)) return false
            var matched = 0
            for (place in a) {
                if (b.any { near(place, it) }) matched++
            }
            return matched >= a.size * AGREE
        }

        private fun near(a: LinePlace, b: LinePlace): Boolean {
            for (i in 0 until 8) if (abs(a.corners[i] - b.corners[i]) > NEAR) return false
            return true
        }
    }
}
