package com.amar.vault.indexing

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PictureReaderSocketTest {

    private class ScriptedReader(private val answer: FoundText) : PictureReader {
        var reads = 0
        override suspend fun read(picture: Bitmap): FoundText {
            reads++
            return answer
        }
    }

    private fun line(text: String, top: Int, left: Int = 40, confidence: Float = 0.96f): FoundLine =
        FoundLine(text, confidence, Rect(left, top, left + text.length * 22, top + 44))

    private fun block(vararg lines: FoundLine): FoundBlock {
        val boxes = lines.map { it.box!! }
        val box = Rect(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
        return FoundBlock(lines.joinToString("\n") { it.text }, box, lines.toList())
    }

    private fun found(vararg blocks: FoundBlock): FoundText =
        FoundText(blocks.joinToString("\n") { it.text }, blocks.toList())

    private val nothing = FoundText("", emptyList())

    private fun picture(): Bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)

    private fun extractor(english: PictureReader, hindi: PictureReader) =
        ImageContentExtractor(ApplicationProvider.getApplicationContext(), english, hindi)

    @Test
    fun aSureScreenIsStoredFromOneReadByEachReaderInTheOrderItIsReadOnTheScreen() = runBlocking {
        val english = ScriptedReader(
            found(
                block(line("Opens again on the next working day", 900)),
                block(line("Monthly statement", 100)),
                block(line("The balance is carried forward", 400), line("and paid within seven days", 460)),
            )
        )
        val hindi = ScriptedReader(nothing)

        val stored = extractor(english, hindi).extract(picture()).ocrText

        assertEquals(
            listOf(
                "Monthly statement",
                "The balance is carried forward",
                "and paid within seven days",
                "Opens again on the next working day",
            ),
            stored.lines(),
        )
        assertEquals(1, english.reads)
        assertEquals(1, hindi.reads)
    }

    @Test
    fun blocksOnOneRowAreStoredLeftToRight() = runBlocking {
        val english = ScriptedReader(
            found(
                block(line("Articles", 260, left = 780)),
                block(line("Images", 260, left = 60)),
                block(line("Videos", 260, left = 420)),
                block(line("Everything saved this month is listed below", 400)),
            )
        )

        val stored = extractor(english, ScriptedReader(nothing)).extract(picture()).ocrText

        assertEquals(listOf("Images", "Videos", "Articles", "Everything saved this month is listed below"), stored.lines())
    }

    @Test
    fun aPhotoIsReadOnceByEachReaderHoweverUnsureTheLines() = runBlocking {
        val english = ScriptedReader(found(block(line("Platform nine", 300, confidence = 0.31f))))
        val hindi = ScriptedReader(nothing)

        val stored = extractor(english, hindi).extract(picture(), photo = true).ocrText

        assertEquals("Platform nine", stored)
        assertEquals(1, english.reads)
        assertEquals(1, hindi.reads)
    }

    @Test
    fun anUnsureLineSendsTheScreenToBeReadAgainAndItsWordsAreKept() = runBlocking {
        val english = ScriptedReader(
            found(
                block(line("Monthly statement for the whole of the year", 100)),
                block(line("Balance carried forward", 400, confidence = 0.42f)),
                block(line("Paid within seven working days of the notice", 700)),
            )
        )
        val hindi = ScriptedReader(nothing)

        val stored = extractor(english, hindi).extract(picture()).ocrText

        assertTrue(stored, "Balance carried forward" in stored.lines())
        assertTrue(stored, "Monthly statement for the whole of the year" in stored.lines())
        assertTrue("read again: ${english.reads}", english.reads > 1)
        assertTrue("read again: ${hindi.reads}", hindi.reads > 1)
    }

    @Test
    fun aQuickReadStoresWhatTheFirstTwoReadsSawAndPutsTheRestOff() = runBlocking {
        val english = ScriptedReader(
            found(
                block(line("Monthly statement for the whole of the year", 100)),
                block(line("Balance carried forward", 400, confidence = 0.42f)),
                block(line("Paid within seven working days of the notice", 700)),
            )
        )
        val hindi = ScriptedReader(nothing)

        val content = extractor(english, hindi).extract(picture(), quick = true)

        assertTrue(content.deeper)
        assertEquals(1, english.reads)
        assertEquals(1, hindi.reads)
        assertTrue(content.ocrText, "Balance carried forward" in content.ocrText.lines())
        assertTrue(content.ocrText, "Paid within seven working days of the notice" in content.ocrText.lines())
    }

    @Test
    fun aQuickReadOfASureScreenLeavesNothingForLater() = runBlocking {
        val english = ScriptedReader(
            found(
                block(line("Monthly statement for the whole of the year", 100)),
                block(line("Paid within seven working days of the notice", 700)),
            )
        )

        val content = extractor(english, ScriptedReader(nothing)).extract(picture(), quick = true)

        assertFalse(content.deeper)
    }

    @Test
    fun aFullReadNeverPutsAnythingOff() = runBlocking {
        val english = ScriptedReader(
            found(
                block(line("Monthly statement for the whole of the year", 100)),
                block(line("Balance carried forward", 400, confidence = 0.42f)),
            )
        )

        val content = extractor(english, ScriptedReader(nothing)).extract(picture())

        assertFalse(content.deeper)
        assertTrue("read again: ${english.reads}", english.reads > 1)
    }

    @Test
    fun aBlockWithoutAPlaceLeavesTheTextAsTheReaderGaveIt() = runBlocking {
        val lines = listOf(
            FoundLine("Opens again on the next working day", 0.96f, null),
            FoundLine("Monthly statement for the whole year", 0.96f, null),
        )
        val english = ScriptedReader(
            FoundText(lines.joinToString("\n") { it.text }, lines.map { FoundBlock(it.text, null, listOf(it)) })
        )

        val stored = extractor(english, ScriptedReader(nothing)).extract(picture()).ocrText

        assertEquals(lines.map { it.text }, stored.lines())
    }
}
