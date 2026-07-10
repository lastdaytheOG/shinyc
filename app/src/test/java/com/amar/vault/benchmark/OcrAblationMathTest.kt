package com.amar.vault.benchmark

import com.amar.vault.indexing.OcrStrategy
import com.amar.vault.indexing.RecordedRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint E2 — pure-JVM tests for the OCR ablation replay math.
 * No Android, no native libs: these must run green on any host.
 */
class OcrAblationMathTest {

    private fun run(s: OcrStrategy, text: String, ms: Double) = RecordedRun(s, text, ms)

    // ── mergeLines mirrors the production merge ───────────────────────────────

    @Test
    fun `merge dedupes case and whitespace variants`() {
        val merged = OcrAblationMath.mergeLines(listOf("Hello World", "hello   world\nBye"))
        assertEquals(listOf("Hello World", "Bye"), merged)
    }

    @Test
    fun `merge evicts substrings from the dedup set but keeps already-emitted lines`() {
        // Production semantics: when a longer superset line arrives, the shorter line is removed
        // from `seen` (so future dedup checks use the longer one) but it has ALREADY been emitted
        // — both lines appear in the merged output. The mirror must reproduce this exactly.
        val merged = OcrAblationMath.mergeLines(listOf("total", "grand total 450"))
        assertEquals(listOf("total", "grand total 450"), merged)
    }

    @Test
    fun `merge skips lines already covered by an earlier longer line`() {
        val merged = OcrAblationMath.mergeLines(listOf("grand total 450", "total"))
        assertEquals(listOf("grand total 450"), merged)
    }

    // ── evaluate: leave-one-out ablation ──────────────────────────────────────

    @Test
    fun `fully redundant strategy loses nothing and saves its own latency`() {
        val runs = listOf(
            run(OcrStrategy.RAW, "alpha beta\ngamma", 100.0),
            run(OcrStrategy.GRAYSCALE, "alpha beta\ngamma", 40.0), // pure duplicate
        )
        val img = OcrAblationMath.evaluate("img1", runs)!!
        val gray = img.ablations.first { it.strategy == OcrStrategy.GRAYSCALE }
        assertEquals(0, gray.lostLines)
        assertEquals(0, gray.lostChars)
        assertEquals(0, gray.lostWords)
        assertEquals(100.0, gray.lineRecallPct, 1e-9)
        assertEquals(100.0, gray.charRecallPct, 1e-9)
        assertEquals(40.0, gray.latencySavedMs, 1e-9)
        // RAW (100ms) still runs → parallel wall time unchanged by dropping the 40ms strategy.
        assertEquals(0.0, gray.wallSavedMs, 1e-9)
    }

    @Test
    fun `unique contributor loses exactly its lines chars and words`() {
        val runs = listOf(
            run(OcrStrategy.RAW, "alpha beta", 100.0),
            run(OcrStrategy.INVERT, "hidden text", 30.0), // only INVERT saw this
        )
        val img = OcrAblationMath.evaluate("img2", runs)!!
        assertEquals(2, img.fullLines)
        val inv = img.ablations.first { it.strategy == OcrStrategy.INVERT }
        assertEquals(1, inv.lostLines)
        assertEquals("hidden text".length, inv.lostChars)
        assertEquals(2, inv.lostWords)
        assertEquals(50.0, inv.lineRecallPct, 1e-9)
    }

    @Test
    fun `removal that promotes a longer duplicate from another strategy is coverage not loss`() {
        // The full merge keeps both "total 450" (RAW) and the superset "grand total 450"
        // (TESSERACT). Removing RAW leaves only the superset line, which still COVERS
        // "total 450" under the merge's own containment test — coverage, not loss.
        val runs = listOf(
            run(OcrStrategy.RAW, "total 450", 80.0),
            run(OcrStrategy.TESSERACT, "grand total 450", 60.0),
        )
        val img = OcrAblationMath.evaluate("img3", runs)!!
        val raw = img.ablations.first { it.strategy == OcrStrategy.RAW }
        assertEquals(0, raw.lostLines)
        assertEquals(100.0, raw.lineRecallPct, 1e-9)
    }

    @Test
    fun `empty merge returns null instead of fabricating perfect recall`() {
        assertNull(OcrAblationMath.evaluate("blank", listOf(run(OcrStrategy.RAW, "  \n ", 10.0))))
        assertNull(OcrAblationMath.evaluate("none", emptyList()))
    }

    @Test
    fun `wall saved is positive only when the slowest strategy is removed`() {
        val runs = listOf(
            run(OcrStrategy.RAW, "a line here", 50.0),
            run(OcrStrategy.UPSCALE, "another line", 200.0),
        )
        val img = OcrAblationMath.evaluate("img4", runs)!!
        assertEquals(150.0, img.ablations.first { it.strategy == OcrStrategy.UPSCALE }.wallSavedMs, 1e-9)
        assertEquals(0.0, img.ablations.first { it.strategy == OcrStrategy.RAW }.wallSavedMs, 1e-9)
    }

    // ── evaluate: escalation ladder ───────────────────────────────────────────

    @Test
    fun `ladder recall is monotonically non-decreasing and reaches 100 at the full set`() {
        val runs = listOf(
            run(OcrStrategy.RAW, "one\ntwo", 100.0),
            run(OcrStrategy.GRAYSCALE, "three", 40.0),
            run(OcrStrategy.INVERT, "four", 30.0),
            run(OcrStrategy.UPSCALE, "five", 120.0),
            run(OcrStrategy.TESSERACT, "six", 300.0),
        )
        val img = OcrAblationMath.evaluate("img5", runs)!!
        assertEquals(6, img.fullLines)
        val recall = img.ladderRecallPct
        assertEquals(OcrAblationMath.LADDER.size, recall.size)
        for (i in 1 until recall.size) {
            assertTrue("rung ${i + 1} (${recall[i]}) < rung $i (${recall[i - 1]})", recall[i] >= recall[i - 1])
        }
        assertEquals(100.0, recall.last(), 1e-9)
        // Rung 1 = RAW only → 2 of 6 lines.
        assertEquals(100.0 * 2 / 6, recall[0], 1e-9)
        // Rung 2 = RAW + TESSERACT → 3 of 6, cumulative sequential compute 400ms.
        assertEquals(100.0 * 3 / 6, recall[1], 1e-9)
        assertEquals(400.0, img.ladderCumMs[1], 1e-9)
    }

    @Test
    fun `ladder cumulative latency sums only present strategies`() {
        val runs = listOf(
            run(OcrStrategy.RAW, "solo line", 75.0), // Tesseract etc. never ran
        )
        val img = OcrAblationMath.evaluate("img6", runs)!!
        assertNotNull(img)
        assertEquals(100.0, img.ladderRecallPct[0], 1e-9)
        assertEquals(75.0, img.ladderCumMs.last(), 1e-9)
    }

    @Test
    fun `full merged text matches production join semantics`() {
        val runs = listOf(
            run(OcrStrategy.RAW, "Line A\nline b", 10.0),
            run(OcrStrategy.GRAYSCALE, "LINE B\nLine C", 10.0),
        )
        val img = OcrAblationMath.evaluate("img7", runs)!!
        assertEquals("Line A\nline b\nLine C", img.fullMergedText)
    }
}
