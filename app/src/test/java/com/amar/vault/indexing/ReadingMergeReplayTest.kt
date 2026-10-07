package com.amar.vault.indexing

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Puts the readings recorded off real pictures through the merge again, without a phone: a
 * way to see what a change to [ReadingMerge] does in seconds.
 *
 * It needs a recording made by `PictureOcrMeasureDeviceTest`, which is of the developer's own
 * pictures and is not kept with the code; without one it does nothing. Given
 * `tools/phone-screenshots/results/<name>.jsonl` it writes `replayed/<name>.jsonl`, and
 * `replayed/<name>-without-<PASS>.jsonl` for the merge with each pass left out, to be scored
 * with `tools/vault-check/ocr_score.py`. It asserts nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ReadingMergeReplayTest {

    @Test
    fun replay() {
        val folder = File("../tools/phone-screenshots/results")
        val recordings = folder.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
        assumeTrue("no recording in ${folder.absolutePath}", recordings.isNotEmpty())
        val out = File(folder, "replayed").apply { mkdirs() }
        for (recording in recordings) {
            val pictures = recording.readLines().filter { it.isNotBlank() }
            val passNames = JSONObject(pictures.first()).getJSONArray("passes").let { all -> (0 until all.length()).map { all.getJSONObject(it).getString("pass") } }
            for (without in listOf<String?>(null) + passNames) {
                val name = recording.nameWithoutExtension + (without?.let { "-without-$it" } ?: "") + ".jsonl"
                File(out, name).bufferedWriter().use { file ->
                    for (line in pictures) {
                        val picture = JSONObject(line)
                        val passes = picture.getJSONArray("passes")
                        val readings = (0 until passes.length()).map { passes.getJSONObject(it) }
                            .filter { it.getString("pass") != without }.map { it.getString("text") }
                        picture.put("text", ReadingMerge.text(readings))
                        file.write(picture.toString())
                        file.newLine()
                    }
                }
            }
        }
    }
}
