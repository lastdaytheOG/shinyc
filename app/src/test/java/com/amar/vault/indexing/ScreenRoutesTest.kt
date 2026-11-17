package com.amar.vault.indexing

import android.graphics.Bitmap
import com.amar.vault.indexing.ppocr.HeldChoice
import com.amar.vault.indexing.ppocr.RememberedChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ScreenRoutesTest {

    private var now = 0L

    private class Pipeline(private val timing: Boolean = false) : PictureReader {
        override val trying: Boolean get() = timing
        override suspend fun read(picture: Bitmap): FoundText = FoundText("", emptyList())
    }

    private class Lines(val reader: PictureReader?, val memory: RememberedChoice?) : ScreenLines {
        var asked = 0
        var released = false
        override suspend fun pipeline(): PictureReader? {
            asked++
            return reader
        }

        override fun remembered(): RememberedChoice? = memory
        override fun release() {
            released = true
        }
    }

    private inner class Reading(
        private val ms: Long,
        private val text: String?,
    ) {
        var reads = 0
        suspend fun read(): String? {
            reads++
            now += ms * 1_000_000
            return text
        }
    }

    private val screen = "Garden notice for the whole village\nThe gate opens at seven each morning"
    private val sameWords = "garden notice for the whole village the gate opens at seven each morning"
    private val otherWords = "Monthly statement paid within working days"

    private fun routes(lines: Lines) = ScreenRoutes(lines) { now }

    private suspend fun ScreenRoutes.readWith(byLines: Reading, whole: Reading): String =
        read({ byLines.read() }) { whole.read()!! }

    @After
    fun backToChosen() {
        LineReadingControl.route = LineReadingControl.Route.CHOSEN
    }

    @Test
    fun withoutLineReadersEveryScreenIsReadWholeAndTheLinesAreNotOpened() = runBlocking {
        val lines = Lines(Pipeline(), null)
        val byLines = Reading(100, screen)
        val whole = Reading(300, sameWords)
        val routes = routes(lines)

        repeat(3) { assertEquals(sameWords, routes.readWith(byLines, whole)) }

        assertEquals(0, lines.asked)
        assertEquals(0, byLines.reads)
        assertEquals(3, whole.reads)
        assertNull(routes.chosen)
    }

    @Test
    fun aRememberedWholeRouteIsKeptWithoutOpeningTheLines() = runBlocking {
        val lines = Lines(Pipeline(), HeldChoice(ScreenRoutes.WHOLE))
        val byLines = Reading(100, screen)
        val whole = Reading(300, sameWords)

        assertEquals(sameWords, routes(lines).readWith(byLines, whole))

        assertEquals(0, lines.asked)
        assertEquals(0, byLines.reads)
    }

    @Test
    fun aRememberedLinesRouteReadsByLinesOnly() = runBlocking {
        val lines = Lines(Pipeline(), HeldChoice(ScreenRoutes.LINES))
        val byLines = Reading(100, screen)
        val whole = Reading(300, sameWords)
        val routes = routes(lines)

        repeat(4) { assertEquals(screen, routes.readWith(byLines, whole)) }

        assertEquals(4, byLines.reads)
        assertEquals(0, whole.reads)
    }

    @Test
    fun whileTheLinePartsAreStillBeingTimedScreensAreReadByLinesWithoutATrial() = runBlocking {
        val pipeline = Pipeline(timing = true)
        val memory = HeldChoice()
        val routes = routes(Lines(pipeline, memory))
        val byLines = Reading(100, screen)
        val whole = Reading(300, sameWords)

        repeat(3) { assertEquals(screen, routes.readWith(byLines, whole)) }

        assertEquals(3, byLines.reads)
        assertEquals(0, whole.reads)
        assertNull(memory.read())
    }

    @Test
    fun linePartsThatNeverSettleDoNotHoldTheTrialsBackForever() = runBlocking {
        val memory = HeldChoice()
        val routes = routes(Lines(Pipeline(timing = true), memory))
        val byLines = Reading(100, screen)
        val whole = Reading(300, sameWords)

        repeat(ScreenRoutes.WARM_AT_MOST) { routes.readWith(byLines, whole) }
        assertEquals(0, whole.reads)
        repeat(ScreenRoutes.TRIALS) { routes.readWith(byLines, whole) }

        assertEquals(ScreenRoutes.LINES, memory.read())
    }

    @Test
    fun whenLinesAreFasterFivePicturesAreReadBothWaysAndLinesAreKept() = runBlocking {
        val memory = HeldChoice()
        val lines = Lines(Pipeline(), memory)
        val routes = routes(lines)
        val byLines = Reading(100, screen)
        val whole = Reading(300, sameWords)

        repeat(ScreenRoutes.TRIALS) { assertEquals(screen, routes.readWith(byLines, whole)) }

        assertEquals(ScreenRoutes.LINES, memory.read())
        assertEquals(ScreenRoutes.LINES, routes.chosen)
        assertEquals(ScreenRoutes.TRIALS, byLines.reads)
        assertEquals(ScreenRoutes.TRIALS + 1, whole.reads)
        assertFalse(lines.released)

        routes.readWith(byLines, whole)
        assertEquals(ScreenRoutes.TRIALS + 1, byLines.reads)
        assertEquals(ScreenRoutes.TRIALS + 1, whole.reads)
    }

    @Test
    fun linesSomewhatSlowerAreStillKeptForTheirBetterReading() = runBlocking {
        val memory = HeldChoice()
        val routes = routes(Lines(Pipeline(), memory))

        repeat(ScreenRoutes.TRIALS) { routes.readWith(Reading(240, screen), Reading(200, sameWords)) }

        assertEquals(ScreenRoutes.LINES, memory.read())
    }

    @Test
    fun linesMuchSlowerGiveWayToTheWholeRouteAndAreClosed() = runBlocking {
        val memory = HeldChoice()
        val lines = Lines(Pipeline(), memory)
        val routes = routes(lines)
        val byLines = Reading(400, screen)
        val whole = Reading(200, sameWords)

        repeat(ScreenRoutes.TRIALS) { assertEquals(screen, routes.readWith(byLines, whole)) }

        assertEquals(ScreenRoutes.WHOLE, memory.read())
        assertTrue(lines.released)
        val asked = lines.asked
        assertEquals(sameWords, routes.readWith(byLines, whole))
        assertEquals(ScreenRoutes.TRIALS, byLines.reads)
        assertEquals(asked, lines.asked)
    }

    @Test
    fun linesThatShareTooFewWordsWithTheWholeRouteAreNotKeptHoweverFast() = runBlocking {
        val memory = HeldChoice()
        val routes = routes(Lines(Pipeline(), memory))

        repeat(ScreenRoutes.TRIALS) { routes.readWith(Reading(50, otherWords), Reading(300, sameWords)) }

        assertEquals(ScreenRoutes.WHOLE, memory.read())
    }

    @Test
    fun whenLinesFailDuringATrialTheWholeReadingIsStored() = runBlocking {
        val memory = HeldChoice()
        val routes = routes(Lines(Pipeline(), memory))

        val stored = routes.readWith(Reading(50, null), Reading(300, sameWords))

        assertEquals(sameWords, stored)
    }

    @Test
    fun blankScreensAreJudgedOnTimeAlone() = runBlocking {
        val memory = HeldChoice()
        val routes = routes(Lines(Pipeline(), memory))

        repeat(ScreenRoutes.TRIALS) { routes.readWith(Reading(100, null), Reading(300, "")) }

        assertEquals(ScreenRoutes.LINES, memory.read())
    }

    @Test
    fun theTwoRoutesTakeTurnsGoingFirst() = runBlocking {
        val routes = routes(Lines(Pipeline(), HeldChoice()))
        val log = ArrayList<String>()

        repeat(3) {
            routes.read({ log += "lines"; screen }) { log += "whole"; sameWords }
        }

        assertEquals(listOf("whole", "lines", "whole", "whole", "lines", "lines", "whole"), log)
    }

    @Test
    fun aForcedRouteOverridesTheChoice() = runBlocking {
        val memory = HeldChoice(ScreenRoutes.WHOLE)
        val routes = routes(Lines(Pipeline(), memory))
        LineReadingControl.route = LineReadingControl.Route.LINES

        assertEquals(screen, routes.readWith(Reading(100, screen), Reading(300, sameWords)))
    }
}
