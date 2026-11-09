package com.amar.vault.indexing

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Puts the reads of real and drawn screens, as `PictureOcrMeasureDeviceTest -e ocr reads` recorded
 * them one at a time, through the app's own merge under several plans for which reads to make: what
 * each plan would have stored, without a phone, to be scored against the text the screens say.
 *
 * It needs a recording, which is of the developer's own pictures and not kept with the code: without
 * one it does nothing. Given `READS_FILE=<reads.jsonl>` in the environment it writes `<reads.jsonl>.<plan>.jsonl` beside
 * it, in the form `tools/vault-check/ocr_score.py` reads. It asserts nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ScreenReadsReplayTest {

    private class Reads(
        val en: String, val hi: String, val enGray: String, val hiGray: String, val enInvert: String, val enUpscale: String,
        val unsure: Boolean = false,
    )

    private val hindiWord = Regex("[\\u0900-\\u097F]{3}")

    /** Each plan says what the readings given to the merge are. */
    private val plans: Map<String, (Reads) -> List<String>> = buildMap {
        put("all-six") { r -> listOf(mergeRead(r.en, r.hi), mergeRead(r.enGray, r.hiGray), r.enInvert, r.enUpscale) }
        fun hindi(r: Reads) = hindiWord.containsMatchIn(r.hi) || hindiWord.containsMatchIn(r.hiGray)
        fun six(r: Reads) = listOf(mergeRead(r.en, r.hi), mergeRead(r.enGray, r.hiGray), r.enInvert, r.enUpscale)
        fun plan(name: String, noHindi: (Reads) -> List<String>) = put(name) { r -> if (hindi(r)) six(r) else noHindi(r) }
        // What the app does: with no Hindi seen, the English read of the grey picture is left out.
        plan("five-reads") { r -> listOf(mergeRead(r.en, r.hi), r.hiGray, r.enInvert, r.enUpscale) }
        // Faster, and not as careful with numbers: the English read as it is and enlarged left out too.
        plan("four-reads") { r -> listOf(r.hi, mergeRead(r.enGray, r.hiGray), r.enInvert) }
        put("tier1-gated") { r ->
            val raw = mergeRead(r.en, r.hi)
            val stored = com.amar.vault.UnicodeText.nfc(ReadingMerge.text(listOf(raw)))
            if (tier1Healthy(stored)) listOf(raw)
            else if (hindi(r)) six(r)
            else listOf(raw, r.hiGray, r.enInvert, r.enUpscale)
        }
        put("tier1-confident") { r ->
            val raw = mergeRead(r.en, r.hi)
            if (gates.getValue("tier1-confident")(r)) listOf(raw)
            else if (hindi(r)) six(r)
            else listOf(raw, r.hiGray, r.enInvert, r.enUpscale)
        }
    }

    private val gates: Map<String, (Reads) -> Boolean> = mapOf(
        "tier1-gated" to { r -> tier1Healthy(storedFirstRead(r)) },
        "tier1-confident" to { r -> !r.unsure && tier1Healthy(storedFirstRead(r)) },
    )

    private fun storedFirstRead(r: Reads): String =
        com.amar.vault.UnicodeText.nfc(ReadingMerge.text(listOf(mergeRead(r.en, r.hi))))

    private fun tier1Healthy(text: String): Boolean {
        if (text.isBlank()) return false
        var words = 0
        var inWord = false
        for (ch in text) {
            if (ch.isWhitespace()) inWord = false
            else if (!inWord) { words++; inWord = true }
        }
        if (words < 8) return false
        return TextTrustScorer.score(text).trusted
    }

    /** What the app does with the two readers' text of one version of a picture. */
    private fun mergeRead(en: String, hi: String): String = when {
        en.isBlank() -> hi
        hi.isBlank() -> en
        en.length > hi.length * 2 -> en
        hi.length > en.length * 2 -> hi
        else -> ReadingMerge.text(listOf(en, hi))
    }

    @Test
    fun tier1Facts() {
        val path = System.getenv("READS_FILE")
        assumeTrue("run with READS_FILE=<reads.jsonl>", path != null)
        val recording = File(path!!)
        File(recording.path + ".tier1-facts.jsonl").bufferedWriter().use { file ->
            for (picture in recording.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }) {
                val reads = picture.getJSONArray("reads")
                val byName = (0 until reads.length()).map { reads.getJSONObject(it) }.associateBy { it.getString("read") }
                fun t(n: String) = LookAlikeDigits.repaired(byName.getValue(n).getString("text"))
                val stored = com.amar.vault.UnicodeText.nfc(ReadingMerge.text(listOf(mergeRead(t("EN_RAW"), t("HI_RAW")))))
                var words = 0
                var inWord = false
                for (ch in stored) {
                    if (ch.isWhitespace()) inWord = false
                    else if (!inWord) { words++; inWord = true }
                }
                file.write(JSONObject().put("name", picture.getString("name")).put("text", stored).put("words", words)
                    .put("trusted", stored.isNotBlank() && TextTrustScorer.score(stored).trusted).toString())
                file.newLine()
            }
        }
    }

    @Test
    fun replay() {
        val path = System.getenv("READS_FILE")
        assumeTrue("run with READS_FILE=<reads.jsonl>", path != null)
        val recording = File(path!!)
        val pictures = recording.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        for ((name, plan) in plans) {
            File(recording.path + "." + name + ".jsonl").bufferedWriter().use { file ->
                for (picture in pictures) {
                    val reads = picture.getJSONArray("reads")
                    val byName = (0 until reads.length()).map { reads.getJSONObject(it) }.associateBy { it.getString("read") }
                    fun t(n: String) = LookAlikeDigits.repaired(byName.getValue(n).getString("text"))
                    fun unsure(n: String): Boolean {
                        val lines = byName.getValue(n).optJSONArray("lines") ?: return false
                        return (0 until lines.length()).map { lines.getJSONObject(it) }.any {
                            ImageContentExtractor.unsureLine(it.getString("text"), it.getDouble("confidence").toFloat())
                        }
                    }
                    val r = Reads(
                        t("EN_RAW"), t("HI_RAW"), t("EN_GRAY"), t("HI_GRAY"), t("EN_INVERT"), t("EN_UPSCALE"),
                        unsure = unsure("EN_RAW") || unsure("HI_RAW"),
                    )
                    val readings = plan(r)
                    file.write(JSONObject().apply {
                        put("name", picture.getString("name"))
                        put("width", picture.getInt("width")).put("height", picture.getInt("height"))
                        put("text", ReadingMerge.text(readings))
                        put("ms", 0)
                        gates[name]?.let { put("accepted", it(r)) }
                    }.toString())
                    file.newLine()
                }
            }
        }
    }
}
