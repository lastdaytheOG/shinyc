package com.amar.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.indexing.OcrStrategyRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A picture is read by the app's own picture path, with the real readers, and what is stored
 * is what the picture says: each line once, in the order it is read on the screen, Hindi
 * beside English, and no line lost to a longer one.
 *
 * The picture is drawn here, so what it says is known exactly. The control is the merge as it
 * was, given the very same readings: it stores lines more than once and loses a number.
 */
@RunWith(AndroidJUnit4::class)
class PictureReadingDeviceTest {

    private class Placed(val text: String, val x: Float, val y: Float, val size: Float = 44f)

    /** A screen: a title, a row of three labels, a paragraph, two page lines, Hindi, two cards. */
    private val screen = listOf(
        Placed("Monthly statement", 60f, 130f, 60f),
        Placed("Images", 60f, 260f), Placed("Videos", 420f, 260f), Placed("Articles", 780f, 260f),
        Placed("The balance is carried forward", 60f, 400f),
        Placed("to the next working day and", 60f, 460f),
        Placed("paid within seven days", 60f, 520f),
        Placed("Opens at page 300", 60f, 680f),
        Placed("Opens at page 30", 60f, 800f),
        Placed("कुल राशि चार सौ रुपये", 60f, 940f, 52f),
        Placed("Saved Item", 60f, 1100f), Placed("18:09", 820f, 1100f),
        Placed("Saved Item", 60f, 1240f), Placed("18:08", 820f, 1240f),
    )

    private fun draw(): Bitmap {
        val bitmap = Bitmap.createBitmap(1080, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        for (piece in screen) {
            paint.textSize = piece.size
            canvas.drawText(piece.text, piece.x, piece.y, paint)
        }
        return bitmap
    }

    private val word = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private fun wordsOf(text: String) = word.findAll(text.lowercase()).map { it.value }.toList()

    /** The merge as it was, copied from ImageContentExtractor.mergeAllOcrResults. */
    private fun formerMerge(readings: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<String>()
        for (text in readings) for (line in text.split("\n")) {
            val cleaned = line.trim()
            if (cleaned.isEmpty()) continue
            val normalized = cleaned.lowercase().replace(Regex("\\s+"), " ")
            if (seen.any { it.contains(normalized) }) continue
            seen.removeAll { normalized.contains(it) }
            seen.add(normalized)
            unique.add(cleaned)
        }
        return unique
    }

    @Test
    fun whatIsStoredIsWhatThePictureSaysOnceAndInOrder() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val recorder = OcrStrategyRecorder()
        val bitmap = draw()
        val (content, _) = ImageContentExtractor(app).extractInstrumented(bitmap, "drawn-screen", recorder)
        bitmap.recycle()
        val stored = content.ocrText
        Log.i("PictureReading", "stored:\n$stored")

        // Every word of the picture, once each, in the order it is read: nothing added, nothing
        // twice, nothing out of place.
        assertEquals(stored, screen.flatMap { wordsOf(it.text) }, wordsOf(stored))

        val lines = stored.lines().map { it.trim() }
        assertTrue("page 30 is a line of its own", "Opens at page 30" in lines)
        assertTrue("so is page 300", "Opens at page 300" in lines)
        assertTrue("Hindi is read", lines.any { "राशि" in it })

        // The control: the same readings, put together the way they used to be.
        val readings = recorder.runsSnapshot().map { it.text }
        assertTrue("more than one reading took part", readings.count { it.isNotBlank() } >= 3)
        val formerly = formerMerge(readings)
        assertFalse("the control: page 30 was lost to page 300", "Opens at page 30" in formerly)
        assertEquals("the control: 'Saved Item' is on the picture twice and was stored once",
            1, formerly.count { it.equals("Saved Item", ignoreCase = true) })
    }
}
