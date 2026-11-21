package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PaintOverTest {

    private val paper = Color.rgb(250, 248, 240)
    private val ink = Color.rgb(20, 20, 30)

    private class SeeingReader(vararg answers: FoundText) : PictureReader {
        private val answers = answers.toMutableList()
        val seen = mutableListOf<Bitmap>()
        override suspend fun read(picture: Bitmap): FoundText {
            seen += picture
            return if (answers.size > 1) answers.removeAt(0) else answers.first()
        }
    }

    @Before
    fun paintingOn() {
        AdaptiveImageOcrControl.paintSettledLines = true
    }

    @After
    fun back() {
        AdaptiveImageOcrControl.paintSettledLines = false
    }

    private fun line(text: String, top: Int, confidence: Float = 0.96f, left: Int = 40): FoundLine =
        FoundLine(text, confidence, Rect(left, top, left + text.length * 22, top + 44))

    private fun found(vararg lines: FoundLine): FoundText =
        FoundText(lines.joinToString("\n") { it.text }, lines.map { FoundBlock(it.text, Rect(it.box), listOf(it)) })

    private fun picture(): Bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)

    private fun extractor(english: PictureReader, hindi: PictureReader) =
        ImageContentExtractor(ApplicationProvider.getApplicationContext(), english, hindi)

    @Test
    fun aLineWithADigitIsSureOnlyFromAHigherConfidence() {
        assertTrue(PaintOver.settled(line("Platform nine", 100, confidence = 0.80f)))
        assertFalse(PaintOver.settled(line("Platform 9", 100, confidence = 0.80f)))
        assertTrue(PaintOver.settled(line("Platform 9", 100, confidence = 0.91f)))
        assertFalse(PaintOver.settled(line("— —", 100, confidence = 0.99f)))
        assertFalse(PaintOver.settled(FoundLine("Platform nine", 0.99f, null)))
    }

    @Test
    fun unsureSpecksAreDroppedAndShortSureWordsKept() {
        val speck = line("०", 100, confidence = 0.2f)
        val sureShort = line("है", 200, confidence = 0.9f)
        val unsureLong = line("Nothing here yet", 300, confidence = 0.3f)

        val kept = PaintOver.withoutSpecks(found(speck, sureShort, unsureLong))

        assertEquals(listOf("है", "Nothing here yet"), kept.blocks.flatMap { it.lines }.map { it.text })
        assertEquals("है\nNothing here yet", kept.text)
    }

    @Test
    fun theBackgroundIsWhatMostOfTheRingIs() {
        val ring = IntArray(9) { if (it < 6) paper else ink }

        assertEquals(paper, PaintOver.background(ring))
        assertEquals(Color.WHITE, PaintOver.background(IntArray(0)))
    }

    @Test
    fun theHindiReaderGetsAPaintedCopyWhenALineIsSure() = runBlocking {
        val sure = line("Monthly statement for the whole year", 100)
        val unsure = line("Balance carried forward", 400, confidence = 0.6f)
        val source = picture()
        val english = SeeingReader(found(sure, unsure))
        val hindi = SeeingReader(FoundText("", emptyList()))

        extractor(english, hindi).extract(source)

        assertSame(source, english.seen.first())
        assertNotSame(source, hindi.seen.first())
    }

    @Test
    fun theHindiReaderGetsThePictureAsItIsWhenNoLineIsSure() = runBlocking {
        val source = picture()
        val hindi = SeeingReader(FoundText("", emptyList()))

        extractor(SeeingReader(found(line("Balance carried forward", 400, confidence = 0.6f))), hindi).extract(source)

        assertSame(source, hindi.seen.first())
    }

    private val english = listOf(
        "Monthly statement for the whole year",
        "Balance carried forward from last month",
        "Paid within seven working days",
        "Opens again on the next working day",
        "Everything saved this month is listed",
        "Reminder set for the evening",
        "Account holder name and branch",
        "Thank you for banking with us",
    ).mapIndexed { i, text -> line(text, 100 + i * 120) }

    @Test
    fun onAScreenWithoutHindiTheSureLinesStandForTheHindiReadersReadingOfThem() = runBlocking {
        val withDigit = line("Reminder set for 7 pm", 1100, confidence = 0.85f)
        val source = picture()
        val englishReader = SeeingReader(found(*(english + withDigit).toTypedArray()))
        val hindi = SeeingReader(found(line("Reminder set for 7 pm", 1100, confidence = 0.8f), line("०", 1300, confidence = 0.2f)))

        val stored = extractor(englishReader, hindi).extract(source).ocrText.lines()

        assertEquals(1, hindi.seen.size)
        assertNotSame(source, hindi.seen[0])
        assertTrue(stored.toString(), "Reminder set for 7 pm" in stored)
        english.forEach { assertTrue(stored.toString(), it.text in stored) }
        assertTrue(stored.toString(), "०" !in stored)
    }

    @Test
    fun whenThePaintedPictureShowsHindiTheHindiReaderReadsThePictureAsItIs() = runBlocking {
        val hindiLine = line("आज का भुगतान", 1100)
        val source = picture()
        val englishReader = SeeingReader(found(*english.toTypedArray()))
        val hindi = SeeingReader(found(hindiLine), found(*(english + hindiLine).toTypedArray()))

        val stored = extractor(englishReader, hindi).extract(source).ocrText.lines()

        assertEquals(2, hindi.seen.size)
        assertNotSame(source, hindi.seen[0])
        assertSame(source, hindi.seen[1])
        assertTrue(stored.toString(), "आज का भुगतान" in stored)
        english.forEach { assertTrue(stored.toString(), it.text in stored) }
        assertEquals(english.size + 1, stored.size)
    }

    @Test
    fun withPaintingOffTheHindiReaderSeesThePictureAsItIs() = runBlocking {
        AdaptiveImageOcrControl.paintSettledLines = false
        val source = picture()
        val hindi = SeeingReader(FoundText("", emptyList()))

        extractor(SeeingReader(found(line("Monthly statement for the whole year", 100))), hindi).extract(source)

        assertSame(source, hindi.seen.first())
    }
}
