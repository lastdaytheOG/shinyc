package com.amar.vault

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.indexing.OcrStrategyRecorder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Reads the text off real pictures with the app's own picture path and writes down what came
 * out — the stored text, and what each pass of the reader contributed and how long it took —
 * so that it can be scored against what the pictures really say.
 *
 * It asserts nothing about the text. It is run on request only:
 *
 *     adb shell am instrument -w -e class com.amar.vault.PictureOcrMeasureDeviceTest \
 *         -e ocr measure com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The pictures are read from the app's files, `ocr-measure/` (put there with `run-as`), and
 * the result is written beside them as `result.jsonl`, one line per picture. Score it with
 * `tools/vault-check/ocr_score.py`.
 */
@RunWith(AndroidJUnit4::class)
class PictureOcrMeasureDeviceTest {

    @Test
    fun measure() = runBlocking {
        assumeTrue("run with -e ocr measure", InstrumentationRegistry.getArguments().getString("ocr") == "measure")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val folder = File(app.filesDir, "ocr-measure")
        val pictures = folder.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }.orEmpty()
        assertTrue("no pictures in ${folder.path}", pictures.isNotEmpty())

        val reader = ImageContentExtractor(app)
        File(folder, "result.jsonl").bufferedWriter().use { out ->
            for (picture in pictures) {
                val bitmap = BitmapFactory.decodeFile(picture.path) ?: continue
                val recorder = OcrStrategyRecorder()
                val started = System.nanoTime()
                val (content, _) = reader.extractInstrumented(bitmap, picture.name, recorder)
                val tookMs = (System.nanoTime() - started) / 1_000_000
                out.write(JSONObject().apply {
                    put("name", picture.name)
                    put("width", bitmap.width)
                    put("height", bitmap.height)
                    put("text", content.ocrText)
                    put("ms", tookMs)
                    put("passes", JSONArray(recorder.runsSnapshot().map { run ->
                        JSONObject().put("pass", run.strategy.name).put("text", run.text).put("ms", run.elapsedMs)
                    }))
                }.toString())
                out.newLine()
                bitmap.recycle()
            }
        }
    }
}
