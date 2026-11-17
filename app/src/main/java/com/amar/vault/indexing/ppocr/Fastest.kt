package com.amar.vault.indexing.ppocr

import android.graphics.Bitmap
import com.amar.vault.VaultLog
import com.amar.vault.indexing.FoundLine
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePlace
import com.amar.vault.indexing.LineReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.max

interface RememberedChoice {
    fun read(): String?
    fun write(choice: String)
}

class Fastest<S : Any, Q : S>(
    private val what: String,
    private val steady: S,
    private val quick: Map<String, Q>,
    private val remembered: RememberedChoice,
    private val clock: () -> Long = System::nanoTime,
) {

    private val trial = Mutex()

    @Volatile
    private var choice: String? = remembered.read()?.takeIf { it == STEADY || it in quick }

    private val out = HashSet<String>()
    private val times = HashMap<String, Long>()
    private var steadyTime = 0L
    private var trials = 0

    val chosen: String? get() = choice

    suspend fun <R> use(fits: (Q) -> Boolean, same: (R, R) -> Boolean, work: suspend (S) -> R): R {
        if (choice == STEADY || quick.values.none(fits)) return work(steady)
        if (choice != null) return settled(fits, work)
        return trial.withLock {
            if (choice != null) settled(fits, work) else tryAll(fits, same, work)
        }
    }

    private suspend fun <R> settled(fits: (Q) -> Boolean, work: suspend (S) -> R): R {
        val name = choice
        val one = quick[name]?.takeIf(fits) ?: return work(steady)
        return try {
            work(one)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            settle(STEADY, "the $name route failed: ${e.message}")
            work(steady)
        }
    }

    private suspend fun <R> tryAll(fits: (Q) -> Boolean, same: (R, R) -> Boolean, work: suspend (S) -> R): R {
        work(steady)
        val started = clock()
        val expected = work(steady)
        steadyTime += clock() - started
        for ((name, one) in quick) {
            if (name in out || !fits(one)) continue
            try {
                work(one)
                val from = clock()
                val found = work(one)
                val took = clock() - from
                if (same(expected, found)) {
                    times[name] = (times[name] ?: 0L) + took
                } else {
                    drop(name, "it gave another result")
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
            settle(STEADY, "no quicker route can be used")
        } else if (trials >= TRIALS) {
            val best = running.minByOrNull { times.getValue(it) }!!
            val summary = running.joinToString { "$it ${times.getValue(it) / 1_000_000} ms" } +
                ", $STEADY ${steadyTime / 1_000_000} ms over $trials pictures"
            settle(if (times.getValue(best) < steadyTime * NEEDED) best else STEADY, summary)
        }
        return expected
    }

    private fun drop(name: String, why: String) {
        out += name
        VaultLog.i("LineReaders", "$what $name is out: $why")
    }

    private fun settle(to: String, why: String) {
        choice = to
        remembered.write(to)
        VaultLog.i("LineReaders", "$what: $to ($why)")
        for ((name, one) in quick) {
            if (name != to && one is AutoCloseable) runCatching { one.close() }
        }
    }

    companion object {
        const val STEADY = "steady"
        const val TRIALS = 3
        const val NEEDED = 0.85

        fun allowed(count: Int): Int = max(1, count / 20)
    }
}

class FastestLineFinder(
    steady: LineFinder,
    quick: Map<String, SizedLineFinder>,
    remembered: RememberedChoice,
    clock: () -> Long = System::nanoTime,
) : LineFinder {

    private val fastest = Fastest<LineFinder, SizedLineFinder>("line finder", steady, quick, remembered, clock)

    val chosen: String? get() = fastest.chosen

    override val trying: Boolean get() = fastest.chosen == null

    override suspend fun find(picture: Bitmap): List<LinePlace> =
        fastest.use({ it.fits(picture.width, picture.height) }, ::same) { it.find(picture) }

    companion object {
        const val STEADY = Fastest.STEADY
        private const val NEAR = 6f

        fun same(a: List<LinePlace>, b: List<LinePlace>): Boolean {
            if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty()
            val allowed = Fastest.allowed(a.size)
            if (abs(a.size - b.size) > allowed) return false
            var unmatched = 0
            for (place in a) {
                if (b.none { near(place, it) }) unmatched++
            }
            return unmatched <= allowed
        }

        private fun near(a: LinePlace, b: LinePlace): Boolean {
            for (i in 0 until 8) if (abs(a.corners[i] - b.corners[i]) > NEAR) return false
            return true
        }
    }
}

class FastestLineReader(
    what: String,
    steady: LineReader,
    quick: Map<String, LineReader>,
    remembered: RememberedChoice,
    clock: () -> Long = System::nanoTime,
) : LineReader {

    private val fastest = Fastest<LineReader, LineReader>(what, steady, quick, remembered, clock)

    val chosen: String? get() = fastest.chosen

    override val trying: Boolean get() = fastest.chosen == null

    override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> =
        fastest.use({ places.isNotEmpty() }, ::same) { it.read(picture, places) }

    companion object {
        const val KEPT = 0.5f

        fun same(a: List<FoundLine>, b: List<FoundLine>): Boolean {
            if (a.size != b.size) return false
            var counted = 0
            var differ = 0
            for (i in a.indices) {
                if (a[i].confidence < KEPT && b[i].confidence < KEPT) continue
                counted++
                if (a[i].text != b[i].text) differ++
            }
            return differ <= Fastest.allowed(counted)
        }
    }
}
