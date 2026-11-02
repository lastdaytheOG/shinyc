package com.amar.vault.indexing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Puts the reads recorded off scanned pages through different ways of merging them, without a
 * device: a way to see in seconds what a change to how a page's first two reads are put together
 * does. `PictureOcrMeasureDeviceTest` with `-e ocr pages` makes the recording, which is not kept
 * with the code; without one this does nothing. Given `tools/phone-screenshots/pages/<name>.jsonl`
 * it writes `pages/replayed/<name>-<way>.jsonl`, to be scored with
 * `tools/vault-check/compare_readings.py`. Only pages that stopped at the first two reads are
 * written: the others were made of more reads than are recorded.
 *
 * `old` is the merge scanned pages had until 2026-11, kept here as the control: on those pages it
 * must give exactly what the device stored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PageReadingReplayTest {

    private fun oldTwoReads(en: String, hi: String): String {
        if (en.isBlank()) return hi
        if (hi.isBlank()) return en
        if (en.length > hi.length * 2) return en
        if (hi.length > en.length * 2) return hi
        val enLines = en.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val hiLines = hi.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return (enLines + hiLines).distinct().joinToString("\n")
    }

    /** One text at a time, dropping any line that stands inside a line kept before it. */
    private fun oldMerge(text: String): String {
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<String>()
        for (line in text.split("\n")) {
            val cleaned = line.trim()
            if (cleaned.isEmpty()) continue
            val normalized = cleaned.lowercase().replace(Regex("\\s+"), " ")
            if (seen.any { it.contains(normalized) }) continue
            seen.removeAll { normalized.contains(it) }
            seen.add(normalized)
            kept.add(cleaned)
        }
        return kept.joinToString("\n")
    }

    private fun shortcutThenLineUp(en: String, hi: String): String {
        if (en.isBlank()) return hi
        if (hi.isBlank()) return en
        if (en.length > hi.length * 2) return en
        if (hi.length > en.length * 2) return hi
        return ReadingMerge.text(listOf(en, hi))
    }

    private val ways: Map<String, (String, String) -> String> = mapOf(
        "old" to { en, hi -> oldMerge(oldTwoReads(en, hi)) },
        "shortcut" to ::shortcutThenLineUp,
        "new" to { en, hi -> ReadingMerge.text(listOf(shortcutThenLineUp(LookAlikeDigits.repaired(en), LookAlikeDigits.repaired(hi)))) },
    )

    @Test
    fun replay() {
        val folder = File("../tools/phone-screenshots/pages")
        val recordings = folder.listFiles { f -> f.name.endsWith(".jsonl") }.orEmpty()
        assumeTrue("no recording in ${folder.absolutePath}", recordings.isNotEmpty())
        val out = File(folder, "replayed").apply { mkdirs() }
        for (recording in recordings) {
            val pages = recording.readLines().filter { it.isNotBlank() }.map(::JSONObject).filter { !it.getBoolean("escalated") }
            for ((name, way) in ways) for (field in listOf("text", "ordered")) {
                val suffix = if (field == "ordered") "-ordered" else ""
                File(out, "${recording.nameWithoutExtension}-$name$suffix.jsonl").bufferedWriter().use { file ->
                    for (page in pages) {
                        val reads = page.getJSONArray("reads")
                        val en = reads.getJSONObject(0).getString(field)
                        val hi = reads.getJSONObject(1).getString(field)
                        val merged = com.amar.vault.UnicodeText.nfc(way(en, hi))
                        if (name == "old" && field == "text") {
                            assertEquals("the control, on ${page.getString("name")}", page.getString("text"), merged)
                        }
                        val written = JSONObject(page.toString()).put("text", merged)
                        written.remove("reads")
                        file.write(written.toString())
                        file.newLine()
                    }
                }
            }
        }
    }
}
