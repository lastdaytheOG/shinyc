package com.amar.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.ppocr.FastestLineFinder
import com.amar.vault.indexing.ppocr.LineModelFiles
import com.amar.vault.indexing.ppocr.FastestLineReader
import com.amar.vault.indexing.ppocr.LiteRtChip
import com.amar.vault.indexing.ppocr.LiteRtLineFinder
import com.amar.vault.indexing.ppocr.LiteRtLineReader
import com.amar.vault.indexing.ppocr.LiteRtRunner
import com.amar.vault.indexing.ppocr.PpOcrLineFinder
import com.amar.vault.indexing.ppocr.PpOcrLineReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LineReadersDeviceTest {

    private class Placed(val text: String, val x: Float, val y: Float, val size: Float = 44f)

    private val english = listOf(
        Placed("Monthly statement", 60f, 130f, 60f),
        Placed("The balance is carried forward", 60f, 400f),
        Placed("to the next working day and", 60f, 460f),
        Placed("paid within seven days", 60f, 520f),
        Placed("Opens at page 300", 60f, 680f),
        Placed("Ref 48213 of 12/08/2031", 60f, 800f),
    )

    private val hindi = Placed("कुल राशि चार सौ रुपये", 60f, 940f, 52f)

    private fun draw(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for (piece in english + hindi) {
            paint.textSize = piece.size
            canvas.drawText(piece.text, piece.x, piece.y, paint)
        }
        return bitmap
    }

    private fun drawTall(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val words = listOf("Monthly statement", "The balance is carried forward", "paid within seven days", "Ref 48213 of 12/08/2031")
        for (n in 0 until 22) {
            paint.textSize = if (n % 5 == 0) 60f else 40f
            canvas.drawText(words[n % words.size] + " " + (n + 1), 60f, 130f + 100f * n, paint)
        }
        return bitmap
    }

    private fun drawSized(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val words = listOf("Monthly statement", "The balance is carried forward", "paid within seven days", "Ref 48213 of 12/08/2031")
        val rows = ((height - 200) / 100).coerceAtMost(22)
        for (n in 0 until rows) {
            paint.textSize = if (n % 5 == 0) 60f else 40f
            canvas.drawText(words[n % words.size] + " " + (n + 1), 60f, 130f + 100f * n, paint)
        }
        return bitmap
    }

    private suspend fun <T> timed(label: String, work: suspend () -> T): T? = try {
        work()
        val started = System.nanoTime()
        work().also { Log.i("LineReaders", "$label: ${(System.nanoTime() - started) / 1_000_000} ms") }
    } catch (e: Throwable) {
        Log.w("LineReaders", "$label cannot run here: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    @Test
    fun everyFixedSizeFinderFindsTheLinesTheSteadyFinderFinds() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = LineModelFiles(app)
        assumeTrue("put the line readers in ${files.dir}", files.present())
        val models = files.fixedFinders()
        assumeTrue("the pack has no fixed size finder", models.isNotEmpty())
        val manifest = files.manifest()!!
        val runners = LiteRtChip.entries.map { LiteRtRunner(it) }
        val steady = PpOcrLineFinder(files.finder().path, manifest.finder)
        try {
            for (model in models) {
                val picture = drawSized(1080, 1080 * model.height / model.width)
                val expected = timed("steady finder, ${picture.width} x ${picture.height}") { steady.find(picture) }!!
                assertTrue(expected.size >= 10)
                for (runner in runners) {
                    val finder = LiteRtLineFinder(runner, models, manifest.finder)
                    assertTrue(finder.fits(picture.width, picture.height))
                    val found = timed("${runner.chip.label} finder ${model.width} x ${model.height}") { finder.find(picture) }
                    if (runner.chip == LiteRtChip.PROCESSOR) assertTrue(found != null)
                    if (found != null) assertTrue("${found.size} against ${expected.size}", FastestLineFinder.same(expected, found))
                }
                picture.recycle()
            }
        } finally {
            steady.close()
            runners.forEach { it.close() }
        }
    }

    @Test
    fun theFixedWidthReadersReadWhatTheSteadyReadersRead() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = LineModelFiles(app)
        assumeTrue("put the line readers in ${files.dir}", files.present())
        val manifest = files.manifest()!!
        val picture = drawTall()
        val places = PpOcrLineFinder(files.finder().path, manifest.finder).use { it.find(picture) }
        assertEquals(22, places.size)
        val runners = LiteRtChip.entries.map { LiteRtRunner(it) }
        try {
            for (role in listOf(LineModelFiles.ALL, LineModelFiles.NUMBERS)) {
                val models = files.fixedReaders(role)
                if (models.isEmpty()) continue
                val letters = files.letters(role)
                val widths = files.widths(role)
                PpOcrLineReader(files.reader(role).path, letters, widths = widths).use { steady ->
                    val expected = timed("steady reader $role") { steady.read(picture, places) }!!
                    for (runner in runners) {
                        val reader = LiteRtLineReader(runner, models, letters, steady, widths)
                        val read = timed("${runner.chip.label} reader $role") { reader.read(picture, places) }
                        if (runner.chip == LiteRtChip.PROCESSOR) assertTrue(read != null)
                        if (read != null) {
                            val differ = expected.indices.filter { expected[it].text != read[it].text }
                            Log.i("LineReaders", "${runner.chip.label} reader $role differs on: " + differ.joinToString { "${expected[it].text} / ${read[it].text}" })
                            assertTrue(differ.joinToString(), FastestLineReader.same(expected, read))
                        }
                    }
                }
            }
        } finally {
            runners.forEach { it.close() }
            picture.recycle()
        }
    }

    @Test
    fun aDrawnScreenIsFoundLineByLineAndReadByEachReader() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = LineModelFiles(app)
        assumeTrue("put the line readers in ${files.dir}", files.present())
        val manifest = files.manifest()!!
        val picture = draw()

        val finding = System.nanoTime()
        val places = PpOcrLineFinder(files.finder().path, manifest.finder).use { it.find(picture) }
        Log.i("LineReaders", "found ${places.size} lines in ${(System.nanoTime() - finding) / 1_000_000} ms, model load included")
        assertEquals(english.size + 1, places.size)

        val tops = places.map { it.top }
        assertEquals(tops.sorted(), tops)

        PpOcrLineReader(files.reader(LineModelFiles.ALL).path, files.letters(LineModelFiles.ALL)).use { reader ->
            val read = reader.read(picture, places)
            val again = System.nanoTime()
            reader.read(picture, places)
            Log.i("LineReaders", "all, ${(System.nanoTime() - again) / 1_000_000} ms: " + read.joinToString(" | ") { "${it.text} ${it.confidence}" })
            for ((n, line) in english.withIndex()) {
                assertEquals(line.text, read[n].text)
                assertTrue("${line.text}: ${read[n].confidence}", read[n].confidence > 0.8f)
            }
        }
        PpOcrLineReader(files.reader(LineModelFiles.NUMBERS).path, files.letters(LineModelFiles.NUMBERS)).use { reader ->
            val read = reader.read(picture, places)
            Log.i("LineReaders", "numbers: " + read.joinToString(" | ") { "${it.text} ${it.confidence}" })
            assertEquals("Opens at page 300", read[4].text)
            assertEquals("Ref 48213 of 12/08/2031", read[5].text)
        }
        PpOcrLineReader(files.reader(LineModelFiles.HINDI).path, files.letters(LineModelFiles.HINDI)).use { reader ->
            val read = reader.read(picture, places)
            Log.i("LineReaders", "hindi: " + read.joinToString(" | ") { "${it.text} ${it.confidence}" })
            assertTrue(read.last().text, "कुल" in read.last().text && "रुपये" in read.last().text)
        }
        picture.recycle()
    }
}
