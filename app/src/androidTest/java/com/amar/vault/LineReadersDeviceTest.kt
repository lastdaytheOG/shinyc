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
import com.amar.vault.indexing.ppocr.LiteRtLineFinder
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

    @Test
    fun theFixedSizeFinderFindsTheLinesTheSteadyFinderFinds() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val files = LineModelFiles(app)
        assumeTrue("put the line readers in ${files.dir}", files.present())
        val model = files.gpuFinder()
        assumeTrue("the pack has no finder for the graphics chip", model != null)
        val manifest = files.manifest()!!
        val size = manifest.gpuFinder!!
        val picture = drawTall()

        val steady = PpOcrLineFinder(files.finder().path, manifest.finder).use { finder ->
            finder.find(picture)
            val started = System.nanoTime()
            finder.find(picture).also { Log.i("LineReaders", "steady finder: ${it.size} lines in ${(System.nanoTime() - started) / 1_000_000} ms") }
        }
        assertEquals(22, steady.size)

        LiteRtLineFinder(model!!.path, size.width, size.height, manifest.finder, onGpu = false).use { finder ->
            assertTrue(finder.fits(picture.width, picture.height))
            finder.find(picture)
            val started = System.nanoTime()
            val found = finder.find(picture)
            Log.i("LineReaders", "fixed finder on the processor: ${found.size} lines in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue("${found.size} against ${steady.size}", FastestLineFinder.same(steady, found))
        }

        val onGpu = LiteRtLineFinder(model.path, size.width, size.height, manifest.finder, onGpu = true)
        try {
            val found = try {
                onGpu.find(picture)
                val started = System.nanoTime()
                onGpu.find(picture).also {
                    Log.i("LineReaders", "fixed finder on the graphics chip: ${it.size} lines in ${(System.nanoTime() - started) / 1_000_000} ms")
                }
            } catch (e: Throwable) {
                Log.w("LineReaders", "the graphics chip cannot run the finder here: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (found != null) assertTrue("${found.size} against ${steady.size}", FastestLineFinder.same(steady, found))
        } finally {
            onGpu.close()
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
