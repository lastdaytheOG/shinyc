package com.amar.vault.indexing.ppocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.amar.vault.indexing.FoundLine
import com.amar.vault.indexing.LinePlace
import com.amar.vault.indexing.LineReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class FastestLineReaderTest {

    private var now = 0L

    private inner class Reader(
        private val takes: Long,
        private val lines: List<FoundLine>,
        var fails: Throwable? = null,
    ) : LineReader, AutoCloseable {
        var reads = 0
        var closed = false
        override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> {
            reads++
            fails?.let { throw it }
            now += takes
            return lines
        }

        override fun close() {
            closed = true
        }
    }

    private class Memory(var value: String? = null) : RememberedChoice {
        override fun read(): String? = value
        override fun write(choice: String) {
            value = choice
        }
    }

    private fun line(text: String, confidence: Float = 0.95f) = FoundLine(text, confidence, Rect(0, 0, 10, 10))

    private val places = List(4) { LinePlace(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f), 0.9f) }
    private val read = listOf(line("Garden notice"), line("Plot 4821"), line("Rs 1,250.00"), line("ooka", 0.3f))
    private val alike = listOf(line("Garden notice"), line("Plot 4821"), line("Rs 1,250.00"), line("uunae", 0.35f))
    private val unlike = listOf(line("Garden notlce"), line("Plot 4B21"), line("Rs 1,250.00"), line("ooka", 0.3f))

    private fun picture(): Bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)

    private fun reader(steady: LineReader, memory: Memory, vararg quick: Pair<String, LineReader>) =
        FastestLineReader("line reader all", steady, linkedMapOf(*quick), memory, clock = { now })

    @Test
    fun aQuickReaderThatReadsTheSameTextFasterIsChosenAfterThreePictures() = runBlocking {
        val steady = Reader(300, read)
        val quick = Reader(80, alike)
        val memory = Memory()
        val reader = reader(steady, memory, "gpu" to quick)

        repeat(3) { assertSame(read, reader.read(picture(), places)) }

        assertEquals("gpu", memory.value)
        val steadyReads = steady.reads
        assertSame(alike, reader.read(picture(), places))
        assertEquals(steadyReads, steady.reads)
    }

    @Test
    fun aQuickReaderThatReadsOtherWordsIsDroppedHoweverFast() = runBlocking {
        val quick = Reader(10, unlike)
        val memory = Memory()

        assertSame(read, reader(Reader(300, read), memory, "gpu16" to quick).read(picture(), places))

        assertEquals(Fastest.STEADY, memory.value)
        assertTrue(quick.closed)
    }

    @Test
    fun theFastestOfTheReadersThatAgreeIsKept() = runBlocking {
        val onGpu = Reader(90, alike)
        val halfGpu = Reader(40, unlike)
        val onProcessor = Reader(150, read)
        val memory = Memory()
        val reader = reader(Reader(300, read), memory, "gpu" to onGpu, "gpu16" to halfGpu, "litert" to onProcessor)

        repeat(3) { reader.read(picture(), places) }

        assertEquals("gpu", memory.value)
        assertTrue(halfGpu.closed)
        assertTrue(onProcessor.closed)
        assertFalse(onGpu.closed)
    }

    @Test
    fun aPictureWithoutLinesIsNoTrial() = runBlocking {
        val steady = Reader(300, emptyList())
        val quick = Reader(80, emptyList())
        val memory = Memory()

        reader(steady, memory, "gpu" to quick).read(picture(), emptyList())

        assertEquals(0, quick.reads)
        assertEquals(1, steady.reads)
        assertNull(memory.value)
    }

    @Test
    fun aChosenReaderThatLaterFailsHandsBackToTheSteadyOne() = runBlocking {
        val quick = Reader(80, alike)
        val memory = Memory("gpu")
        val reader = reader(Reader(300, read), memory, "gpu" to quick)
        quick.fails = IllegalStateException("lost the device")

        assertSame(read, reader.read(picture(), places))

        assertEquals(Fastest.STEADY, memory.value)
    }

    @Test
    fun onlyLinesThatWouldBeKeptAreComparedAndOneDifferenceIsAllowed() {
        assertTrue(FastestLineReader.same(read, alike))
        assertFalse(FastestLineReader.same(read, unlike))
        assertTrue(FastestLineReader.same(read, listOf(line("Garden notice"), line("Plot 4821"), line("Rs 1.250.00"), line("ooka", 0.3f))))
        assertFalse(FastestLineReader.same(read, read.dropLast(1)))
        assertTrue(FastestLineReader.same(emptyList(), emptyList()))
    }
}
