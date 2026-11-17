package com.amar.vault.indexing.ppocr

import android.graphics.Bitmap
import com.amar.vault.indexing.LineFinder
import com.amar.vault.indexing.LinePlace
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
class FastestLineFinderTest {

    private var now = 0L

    private inner class Finder(
        private val takes: Long,
        private val places: List<LinePlace>,
        private val fitsWidth: Int = 1080,
        var fails: Throwable? = null,
    ) : SizedLineFinder, AutoCloseable {
        var finds = 0
        var closed = false
        override fun fits(pictureWidth: Int, pictureHeight: Int): Boolean = pictureWidth == fitsWidth
        override suspend fun find(picture: Bitmap): List<LinePlace> {
            finds++
            fails?.let { throw it }
            now += takes
            return places
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

    private fun place(top: Float, shift: Float = 0f): LinePlace =
        LinePlace(floatArrayOf(40f + shift, top, 640f + shift, top, 640f + shift, top + 40f, 40f + shift, top + 40f), 0.9f)

    private val lines = listOf(place(100f), place(200f), place(300f))
    private val nearly = listOf(place(101f, 2f), place(199f), place(300f, -3f))
    private val others = listOf(place(100f), place(500f, 80f), place(900f))

    private fun picture(width: Int = 1080): Bitmap = Bitmap.createBitmap(width, 2400, Bitmap.Config.ARGB_8888)

    private fun finder(steady: LineFinder, memory: Memory, vararg quick: Pair<String, SizedLineFinder>) =
        FastestLineFinder(steady, linkedMapOf(*quick), memory, clock = { now })

    @Test
    fun aQuickFinderThatIsFasterAndFindsTheSameLinesIsChosenAfterThreePictures() = runBlocking {
        val steady = Finder(200, lines)
        val quick = Finder(60, nearly)
        val memory = Memory()
        val finder = finder(steady, memory, "gpu" to quick)

        repeat(2) { assertSame(lines, finder.find(picture())) }
        assertNull(memory.value)
        assertSame(lines, finder.find(picture()))

        assertEquals("gpu", memory.value)
        val steadyFinds = steady.finds
        assertSame(nearly, finder.find(picture()))
        assertEquals(steadyFinds, steady.finds)
    }

    @Test
    fun ofTwoUsableQuickFindersTheFasterIsChosenAndTheOtherIsClosed() = runBlocking {
        val onGpu = Finder(120, lines)
        val onProcessor = Finder(70, nearly)
        val memory = Memory()
        val finder = finder(Finder(200, lines), memory, "gpu" to onGpu, "litert" to onProcessor)

        repeat(3) { finder.find(picture()) }

        assertEquals("litert", memory.value)
        assertTrue(onGpu.closed)
        assertFalse(onProcessor.closed)
        assertSame(nearly, finder.find(picture()))
    }

    @Test
    fun aQuickFinderThatCannotStartLeavesTheOtherInTheRunning() = runBlocking {
        val onGpu = Finder(10, lines, fails = UnsatisfiedLinkError("no such library"))
        val onProcessor = Finder(70, nearly)
        val memory = Memory()
        val finder = finder(Finder(200, lines), memory, "gpu" to onGpu, "litert" to onProcessor)

        repeat(3) { assertSame(lines, finder.find(picture())) }

        assertEquals("litert", memory.value)
        assertEquals(1, onGpu.finds)
    }

    @Test
    fun aQuickFinderThatIsNotClearlyFasterIsNotChosen() = runBlocking {
        val steady = Finder(200, lines)
        val quick = Finder(190, lines)
        val memory = Memory()
        val finder = finder(steady, memory, "gpu" to quick)

        repeat(3) { finder.find(picture()) }

        assertEquals(FastestLineFinder.STEADY, memory.value)
        assertTrue(quick.closed)
        val quickFinds = quick.finds
        finder.find(picture())
        assertEquals(quickFinds, quick.finds)
    }

    @Test
    fun aQuickFinderThatFindsOtherLinesIsDroppedAtOnceHoweverFast() = runBlocking {
        val quick = Finder(10, others)
        val memory = Memory()

        val found = finder(Finder(200, lines), memory, "gpu" to quick).find(picture())

        assertSame(lines, found)
        assertEquals(FastestLineFinder.STEADY, memory.value)
    }

    @Test
    fun whenNoQuickFinderCanStartTheSteadyOneIsChosenOnTheFirstPicture() = runBlocking {
        val quick = Finder(10, lines, fails = IllegalStateException("failed to compile model"))
        val memory = Memory()
        val finder = finder(Finder(200, lines), memory, "gpu" to quick)

        assertSame(lines, finder.find(picture()))
        assertEquals(FastestLineFinder.STEADY, memory.value)
        assertSame(lines, finder.find(picture()))
        assertEquals(1, quick.finds)
    }

    @Test
    fun aChosenQuickFinderThatLaterFailsHandsBackToTheSteadyOne() = runBlocking {
        val steady = Finder(200, lines)
        val quick = Finder(60, nearly)
        val memory = Memory("gpu")
        val finder = finder(steady, memory, "gpu" to quick)
        assertSame(nearly, finder.find(picture()))
        quick.fails = IllegalStateException("lost the device")

        assertSame(lines, finder.find(picture()))

        assertEquals(FastestLineFinder.STEADY, memory.value)
    }

    @Test
    fun aPictureNoQuickFinderFitsGoesToTheSteadyOneAndIsNoTrial() = runBlocking {
        val steady = Finder(200, lines)
        val quick = Finder(60, lines)
        val memory = Memory()

        assertSame(lines, finder(steady, memory, "gpu" to quick).find(picture(width = 1920)))

        assertEquals(0, quick.finds)
        assertEquals(1, steady.finds)
        assertNull(memory.value)
    }

    @Test
    fun whatWasChosenBeforeIsUsedWithoutTryingAgain() = runBlocking {
        val steady = Finder(200, lines)
        val quick = Finder(60, nearly)

        assertSame(lines, finder(steady, Memory(FastestLineFinder.STEADY), "gpu" to quick).find(picture()))

        assertEquals(0, quick.finds)
        assertEquals(1, steady.finds)
    }

    @Test
    fun aRememberedChoiceThatIsNoLongerOfferedIsTriedAfresh() = runBlocking {
        val memory = Memory("npu")
        val finder = finder(Finder(200, lines), memory, "gpu" to Finder(60, nearly))

        repeat(3) { finder.find(picture()) }

        assertEquals("gpu", memory.value)
    }

    @Test
    fun withoutAQuickFinderTheSteadyOneFinds() = runBlocking {
        val memory = Memory()

        assertSame(lines, finder(Finder(200, lines), memory).find(picture()))
        assertNull(memory.value)
    }

    @Test
    fun linesAreTheSameWhenNearlyAllHaveAPartnerAFewPixelsAway() {
        assertTrue(FastestLineFinder.same(lines, nearly))
        assertFalse(FastestLineFinder.same(lines, others))
        assertFalse(FastestLineFinder.same(lines, emptyList()))
        assertTrue(FastestLineFinder.same(emptyList(), emptyList()))
    }
}
