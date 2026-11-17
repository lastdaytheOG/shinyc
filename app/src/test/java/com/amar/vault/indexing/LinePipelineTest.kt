package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.indexing.ppocr.HeldChoice
import com.amar.vault.indexing.ppocr.RememberedChoice
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LinePipelineTest {

    private class Finder(private val places: List<LinePlace>) : LineFinder {
        override suspend fun find(picture: Bitmap): List<LinePlace> = places
    }

    private class Reader(private val answer: (LinePlace) -> Pair<String, Float>) : LineReader {
        val asked = ArrayList<List<LinePlace>>()
        override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> {
            asked += places
            return places.map { place -> answer(place).let { FoundLine(it.first, it.second, place.box()) } }
        }
    }

    private class Whole(private val answer: (Bitmap) -> FoundText) : PictureReader {
        var reads = 0
        val seen = ArrayList<Pair<Int, Int>>()
        override suspend fun read(picture: Bitmap): FoundText {
            reads++
            seen += picture.width to picture.height
            return answer(picture)
        }
    }

    private fun place(top: Int, left: Int = 40, width: Int = 600, height: Int = 40): LinePlace {
        val l = left.toFloat()
        val t = top.toFloat()
        val r = (left + width).toFloat()
        val b = (top + height).toFloat()
        return LinePlace(floatArrayOf(l, t, r, t, r, b, l, b), 0.9f)
    }

    private fun picture(): Bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)

    private val nobody = Reader { "" to 0f }

    @After
    fun backToChosen() {
        LineReadingControl.route = LineReadingControl.Route.CHOSEN
    }

    @Test
    fun sureLinesAreReadOnceAndStoredInTheOrderTheyWereFound() = runBlocking {
        val places = listOf(place(100), place(200), place(300))
        val all = Reader { listOf("Garden notice", "The gate opens at seven", "and closes before dark")[places.indexOf(it)] to 0.97f }
        val numbers = Reader { "" to 0f }
        val hindi = Reader { "" to 0f }

        val found = LinePipeline(Finder(places), all, numbers, hindi).read(picture())

        assertEquals("Garden notice\nThe gate opens at seven\nand closes before dark", found.text)
        assertEquals(3, found.blocks.size)
        assertEquals(Rect(40, 200, 640, 240), found.blocks[1].box)
        assertEquals(listOf(places), all.asked)
        assertTrue(numbers.asked.isEmpty())
        assertTrue(hindi.asked.isEmpty())
    }

    @Test
    fun aLineWithADigitIsReadAgainByTheNumberReaderAndThatReadingIsKept() = runBlocking {
        val places = listOf(place(100), place(200))
        val all = Reader { (if (it === places[0]) "Garden notice" else "Plot 48Z1") to 0.95f }
        val numbers = Reader { "Plot 4821" to 0.99f }

        val found = LinePipeline(Finder(places), all, numbers, nobody).read(picture())

        assertEquals("Garden notice\nPlot 4821", found.text)
        assertEquals(listOf(listOf(places[1])), numbers.asked)
    }

    @Test
    fun aNumberLineTheNumberReaderMakesNothingOfKeepsItsFirstReading() = runBlocking {
        val places = listOf(place(100))
        val all = Reader { "Row 17 of 63" to 0.95f }

        val found = LinePipeline(Finder(places), all, Reader { " " to 0.2f }, nobody).read(picture())

        assertEquals("Row 17 of 63", found.text)
    }

    @Test
    fun anUnsureLineGoesToTheHindiReaderAndItsReadingIsKeptWhenItSaysHindi() = runBlocking {
        val places = listOf(place(100), place(200), place(300))
        val all = Reader {
            when (it) {
                places[0] -> "Garden notice" to 0.97f
                places[1] -> "TR" to 0.33f
                else -> "Water is shared" to 0.71f
            }
        }
        val hindi = Reader { (if (it === places[1]) "कुल राशि चार सौ" else "Water is shated") to 0.88f }

        val found = LinePipeline(Finder(places), all, nobody, hindi).read(picture())

        assertEquals("Garden notice\nकुल राशि चार सौ\nWater is shared", found.text)
        assertEquals(listOf(listOf(places[1], places[2])), hindi.asked)
    }

    @Test
    fun aSureLineWithOneWeakWordGoesToTheHindiReaderToo() = runBlocking {
        val places = listOf(place(100), place(200))
        val all = object : LineReader {
            override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> = listOf(
                FoundLine("Garden notice", 0.97f, places[0].box(), 0.95f),
                FoundLine("FG 22/09/2031", 0.93f, places[1].box(), 0.41f),
            )
        }
        val hindi = Reader { (if (it === places[1]) "तारीख 22/09/2031" else "Garden notice") to 0.95f }

        val found = LinePipeline(Finder(places), all, nobody, hindi, weakWordBelow = 0.80f).read(picture())

        assertEquals("Garden notice\nतारीख 22/09/2031", found.text)
        assertEquals(listOf(listOf(places[1])), hindi.asked)
    }

    @Test
    fun whenMostOfTheTextIsUnsureTheHindiReaderReadsTheWholePictureOnce() = runBlocking {
        val places = listOf(place(100), place(200), place(300))
        val all = Reader { (if (it === places[0]) "Garden notice" else "TR") to (if (it === places[0]) 0.97f else 0.33f) }
        val sheets = Reader { "" to 0f }
        val whole = Reader {
            when (it) {
                places[0] -> "Garden notice"
                places[1] -> "कुल राशि चार सौ"
                else -> "पानी सबका है"
            } to 0.9f
        }

        val found = LinePipeline(Finder(places), all, nobody, sheets, whole).read(picture())

        assertEquals("Garden notice\nकुल राशि चार सौ\nपानी सबका है", found.text)
        assertTrue(sheets.asked.isEmpty())
        assertEquals(listOf(places), whole.asked)
    }

    @Test
    fun whenFewLinesAreUnsureTheyStillGoOnSheets() = runBlocking {
        val places = listOf(place(100), place(200), place(300))
        val all = Reader { (if (it === places[1]) "TR" else "Garden notice") to (if (it === places[1]) 0.33f else 0.97f) }
        val sheets = Reader { "कुल राशि चार सौ" to 0.9f }
        val whole = Reader { "" to 0f }

        LinePipeline(Finder(places), all, nobody, sheets, whole).read(picture())

        assertEquals(listOf(listOf(places[1])), sheets.asked)
        assertTrue(whole.asked.isEmpty())
    }

    @Test
    fun aWholePictureReadingGoesBackToTheLinesItSitsOn() = runBlocking {
        val places = listOf(place(100), place(200), place(300))
        fun at(text: String, left: Int, top: Int, width: Int) = FoundLine(text, 0.9f, Rect(left, top, left + width, top + 44))
        val read = FoundText(
            "",
            listOf(
                FoundBlock("", null, listOf(at("राशि", 340, 196, 200), at("कुल", 40, 198, 200))),
                FoundBlock("", null, listOf(at("सौ", 40, 296, 100), at("stray", 40, 600, 100))),
            ),
        )

        val lines = PictureLineReader(Whole { read }).read(picture(), places)

        assertEquals(listOf("", "कुल राशि", "सौ"), lines.map { it.text })
        assertEquals(places[1].box(), lines[1].box)
        assertEquals(0f, lines[0].confidence, 0f)
    }

    @Test
    fun linesOfTwoColumnsAreKeptAsTwoBlocksSoEachColumnIsReadToItsEnd() {
        fun at(text: String, left: Int, top: Int) = FoundLine(text, 0.95f, Rect(left, top, left + 400, top + 40))
        val lines = listOf(
            at("left one", 40, 100), at("right one", 540, 102), at("left two", 40, 150),
            at("right two", 540, 152), at("after a gap", 40, 300),
        )

        val blocks = LineBlocks.of(lines)

        assertEquals(listOf("left one\nleft two", "right one\nright two", "after a gap"), blocks.map { it.text })
        assertEquals(Rect(40, 100, 440, 190), blocks[0].box)
    }

    @Test
    fun aHindiReadingIsKeptHoweverLowTheHindiReaderRatesIt() = runBlocking {
        val places = listOf(place(100), place(200))
        val all = Reader { (if (it === places[0]) "Garden notice" else "T Rs 400") to (if (it === places[0]) 0.97f else 0.6f) }
        val hindi = Reader { "कुल राशि Rs 400" to 0.3f }

        val onPage = LinePipeline(Finder(places), all, nobody, hindi, keepHindi = true).read(picture())
        val onScreen = LinePipeline(Finder(places), all, nobody, hindi).read(picture())

        assertEquals("Garden notice\nकुल राशि Rs 400", onPage.text)
        assertEquals("Garden notice", onScreen.text)
    }

    @Test
    fun withoutTheWeakWordRuleOnlyAnUnsureLineGoesToTheHindiReader() = runBlocking {
        val places = listOf(place(100), place(200))
        val all = object : LineReader {
            override suspend fun read(picture: Bitmap, places: List<LinePlace>): List<FoundLine> = listOf(
                FoundLine("Garden notice", 0.97f, places[0].box(), 0.95f),
                FoundLine("FG 22/09/2031", 0.93f, places[1].box(), 0.41f),
            )
        }
        val hindi = Reader { "तारीख 22/09/2031" to 0.95f }

        val found = LinePipeline(Finder(places), all, nobody, hindi).read(picture())

        assertEquals("Garden notice\nFG 22/09/2031", found.text)
        assertTrue(hindi.asked.isEmpty())
    }

    @Test
    fun linesUnderEachOtherAreOneBlockOnlyWhenGrouped() = runBlocking {
        val places = listOf(place(100), place(150))
        val all = Reader { (if (it === places[0]) "Garden notice" else "opens at seven") to 0.97f }

        val grouped = LinePipeline(Finder(places), all, nobody, nobody, grouped = true).read(picture())
        val apart = LinePipeline(Finder(places), all, nobody, nobody).read(picture())

        assertEquals(listOf("Garden notice\nopens at seven"), grouped.blocks.map { it.text })
        assertEquals(listOf("Garden notice", "opens at seven"), apart.blocks.map { it.text })
    }

    @Test
    fun aLineNoReaderIsSureOfIsLeftOut() = runBlocking {
        val places = listOf(place(100), place(200))
        val all = Reader { (if (it === places[0]) "Garden notice" else "lIl") to (if (it === places[0]) 0.97f else 0.3f) }

        val found = LinePipeline(Finder(places), all, nobody, Reader { "Il" to 0.4f }).read(picture())

        assertEquals("Garden notice", found.text)
        assertEquals(1, found.blocks.size)
    }

    @Test
    fun aPictureWithNoLinesIsNoTextAndNoReaderIsAsked() = runBlocking {
        val all = Reader { "x" to 1f }

        val found = LinePipeline(Finder(emptyList()), all, nobody, nobody).read(picture())

        assertEquals("", found.text)
        assertTrue(all.asked.isEmpty())
    }

    @Test
    fun linesGivenToAWholePictureReaderAreStackedOnASheetAndItsWordsGoBackToTheirLines() = runBlocking {
        val places = listOf(place(100, left = 40, width = 600), place(700, left = 200, width = 300))
        val whole = Whole {
            val upper = FoundLine("कुल राशि", 0.9f, Rect(10, 12, 300, 44))
            val left = FoundLine("चार", 0.8f, Rect(10, 124, 90, 156))
            val right = FoundLine("सौ रुपये", 0.7f, Rect(120, 124, 290, 156))
            FoundText("", listOf(FoundBlock("", null, listOf(right, upper, left))))
        }

        val read = SheetLineReader(whole).read(picture(), places)

        assertEquals(1, whole.reads)
        assertEquals(616 to 168, whole.seen.single())
        assertEquals(listOf("कुल राशि", "चार सौ रुपये"), read.map { it.text })
        assertEquals(0.9f, read[0].confidence, 0f)
        assertEquals(0.7f, read[1].confidence, 0f)
        assertEquals(Rect(200, 700, 500, 740), read[1].box)
    }

    @Test
    fun aTinyLineIsGivenToTheWholePictureReaderOnASheetItAccepts() = runBlocking {
        val whole = Whole { FoundText("", listOf(FoundBlock("", null, listOf(FoundLine("ok", 0.9f, Rect(4, 8, 28, 24)))))) }

        val read = SheetLineReader(whole).read(picture(), listOf(place(2, left = 1070, width = 10, height = 10)))

        assertEquals(32 to 32, whole.seen.single())
        assertEquals("ok", read.single().text)
    }

    @Test
    fun aLineTheWholePictureReaderSawNothingInComesBackEmpty() = runBlocking {
        val read = SheetLineReader(Whole { FoundText("", emptyList()) }).read(picture(), listOf(place(100)))

        assertEquals(listOf(""), read.map { it.text })
        assertEquals(0f, read.single().confidence, 0f)
    }

    private fun screen(vararg lines: String): FoundText {
        val found = lines.mapIndexed { n, text -> FoundLine(text, 0.96f, Rect(40, 100 + 100 * n, 40 + 22 * text.length, 144 + 100 * n)) }
        return FoundText(lines.joinToString("\n"), found.map { FoundBlock(it.text, it.box, listOf(it)) })
    }

    private val mlKitSays = screen("Monthly statement for the whole of the year", "Paid within seven working days of the notice")
    private val linesSay = screen("Garden notice for the whole of the village", "The gate opens at seven each morning")

    private class Lines(private val reader: PictureReader?) : ReadingLines {
        var asked = 0
        override suspend fun pipeline(kind: String): PictureReader? {
            asked++
            return reader
        }

        override fun remembered(kind: String): RememberedChoice = HeldChoice()
        override fun release() = Unit
    }

    private fun extractor(english: PictureReader, lines: PictureReader?) = ImageContentExtractor(
        ApplicationProvider.getApplicationContext(), english, Whole { FoundText("", emptyList()) }, Lines(lines),
    )

    @Test
    fun withTheWholeRouteForcedTheLineReadersAreNotEvenAskedFor() = runBlocking {
        LineReadingControl.route = LineReadingControl.Route.WHOLE
        val english = Whole { mlKitSays }
        val lines = Lines(Whole { linesSay })

        val stored = ImageContentExtractor(
            ApplicationProvider.getApplicationContext(), english, Whole { FoundText("", emptyList()) }, lines,
        ).extract(picture()).ocrText

        assertEquals(mlKitSays.text, stored)
        assertEquals(0, lines.asked)
    }

    @Test
    fun withLinesForcedAScreenIsReadByLinesAndTheWholePictureReadersAreLeftAlone() = runBlocking {
        LineReadingControl.route = LineReadingControl.Route.LINES
        val english = Whole { mlKitSays }
        val lines = Whole { linesSay }

        val stored = extractor(english, lines).extract(picture()).ocrText

        assertEquals(linesSay.text, stored)
        assertEquals(1, lines.reads)
        assertEquals(0, english.reads)
    }

    @Test
    fun withLinesForcedAndNoLineReadersTheWholePictureReadersReadIt() = runBlocking {
        LineReadingControl.route = LineReadingControl.Route.LINES

        val stored = extractor(Whole { mlKitSays }, null).extract(picture()).ocrText

        assertEquals(mlKitSays.text, stored)
    }

    @Test
    fun whenReadingByLinesFailsTheWholePictureReadersReadIt() = runBlocking {
        LineReadingControl.route = LineReadingControl.Route.LINES

        val stored = extractor(Whole { mlKitSays }, Whole { throw IllegalStateException("no session") }).extract(picture()).ocrText

        assertEquals(mlKitSays.text, stored)
    }

    @Test
    fun whenReadingByLinesFindsNothingTheWholePictureReadersHaveALook() = runBlocking {
        LineReadingControl.route = LineReadingControl.Route.LINES

        val stored = extractor(Whole { mlKitSays }, Whole { FoundText("", emptyList()) }).extract(picture()).ocrText

        assertEquals(mlKitSays.text, stored)
    }

    @Test
    fun aPhotoIsNotReadByLines() = runBlocking {
        LineReadingControl.route = LineReadingControl.Route.LINES
        val lines = Whole { linesSay }

        val stored = extractor(Whole { mlKitSays }, lines).extract(picture(), photo = true).ocrText

        assertEquals(mlKitSays.text, stored)
        assertEquals(0, lines.reads)
    }
}
