package com.amar.vault.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the OCR scoring math and script classification.
 * No Android, no native libs: these must run green on any host.
 *
 * These cover the four defects that made the OCR module unusable as a gate for NPU-OCR work:
 * missing CER/WER, NFD/NFC mismatch on Devanagari, no per-script split, and (in OcrBenchmark)
 * the wrong production entry point.
 */
class OcrScoringMathTest {

    private val eps = 1e-9

    // ── CER / WER definitions ────────────────────────────────────────────────

    @Test
    fun `cer divides by truth length, not max length`() {
        // truth = "abcd" (4). ocr drops one char -> distance 1 -> CER = 1/4.
        assertEquals(0.25, OcrScoringMath.characterErrorRate("abd", "abcd")!!, eps)
    }

    @Test
    fun `cer and character accuracy disagree when the engine over-produces`() {
        // This is the whole reason CER was added. Truth is 4 chars; the engine emits 8 (a
        // duplicated line). charAccuracy divides by max(len)=8 and reports a soft 0.5;
        // CER divides by len(truth)=4 and reports 1.0 -- one full error per truth character.
        val truth = "abcd"
        val ocr = "abcdabcd"
        val acc = OcrScoringMath.characterAccuracy(ocr, truth)
        val cer = OcrScoringMath.characterErrorRate(ocr, truth)!!
        assertEquals(0.5, acc, eps)
        assertEquals(1.0, cer, eps)
    }

    @Test
    fun `cer exceeds one when the engine hallucinates far more text than truth`() {
        // Not clamped: a runaway must be visibly runaway, not capped at "merely bad".
        val cer = OcrScoringMath.characterErrorRate("abcdefghijklmnop", "abc")!!
        assertTrue("expected CER > 1.0 but was $cer", cer > 1.0)
    }

    @Test
    fun `cer is null when ground truth is empty rather than a flattering zero`() {
        assertNull(OcrScoringMath.characterErrorRate("some output", ""))
        assertNull(OcrScoringMath.characterErrorRate("some output", "   \n  "))
        assertNull(OcrScoringMath.wordErrorRate("some output", ""))
    }

    @Test
    fun `perfect recognition scores zero error`() {
        assertEquals(0.0, OcrScoringMath.characterErrorRate("Hello World", "Hello World")!!, eps)
        assertEquals(0.0, OcrScoringMath.wordErrorRate("Hello World", "Hello World")!!, eps)
    }

    @Test
    fun `wer divides by truth word count`() {
        // 4 truth words, one substituted -> 1/4.
        assertEquals(0.25, OcrScoringMath.wordErrorRate("the quick brown cat", "the quick brown fox")!!, eps)
    }

    // ── Case sensitivity ─────────────────────────────────────────────────────

    @Test
    fun `cer counts case errors while legacy accuracy folds them away`() {
        // A recognizer emitting the wrong case is making a real error. The legacy metric
        // lowercases and cannot see it; CER must.
        val acc = OcrScoringMath.characterAccuracy("HELLO", "hello")
        val cer = OcrScoringMath.characterErrorRate("HELLO", "hello")!!
        assertEquals(1.0, acc, eps)
        assertTrue("CER should penalise case errors, was $cer", cer > 0.0)
    }

    // ── Unicode normalization (the Devanagari trap) ──────────────────────────

    @Test
    fun `NFD ground truth scores identically to NFC after normalization`() {
        // "ऱ्या" style text: same grapheme, different composition form. Without NFC on both
        // sides these compare as different characters and Devanagari CER is inflated by a
        // reason that never appears in the report.
        val nfcText = "र्या"                 // र् य ा
        val nfdText = java.text.Normalizer.normalize(nfcText, java.text.Normalizer.Form.NFD)
        assertEquals(0.0, OcrScoringMath.characterErrorRate(nfcText, nfdText)!!, eps)
        assertEquals(1.0, OcrScoringMath.characterAccuracy(nfcText, nfdText), eps)
    }

    @Test
    fun `precomposed and decomposed forms differ without normalization`() {
        // Guards the premise of the test above: if these were already equal as raw strings,
        // the NFC fix would be pointless and this suite would be proving nothing.
        val composed = "ऩ"  // DEVANAGARI LETTER NNNA (canonically decomposes)
        val decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD)
        assertNotEquals(composed, decomposed)
        assertEquals(OcrScoringMath.nfc(decomposed), OcrScoringMath.nfc(composed))
    }

    @Test
    fun `whitespace differences do not count as errors`() {
        assertEquals(0.0, OcrScoringMath.characterErrorRate("a  b\n\nc", "a b c")!!, eps)
    }

    // ── Script classification ────────────────────────────────────────────────

    private fun case(truth: String, script: String = "", stratum: String = "") = BenchmarkCase(
        id = "t", contentType = BenchmarkContentType.PDF,
        groundTruthText = truth, mediaFile = "m.png", script = script, stratum = stratum,
    )

    @Test
    fun `pure devanagari derives as hi and pure latin as en`() {
        assertEquals(OcrScript.HI to "derived", OcrScript.resolve(case("यह एक वाक्य है")))
        assertEquals(OcrScript.EN to "derived", OcrScript.resolve(case("This is a sentence")))
    }

    @Test
    fun `declared script wins over derivation and is recorded as declared`() {
        // A dataset author overriding the heuristic must be obeyed -- and the row must say so,
        // so a mislabelled dataset is visible rather than silent.
        assertEquals(OcrScript.EN to "declared", OcrScript.resolve(case("यह एक वाक्य है", "en")))
        assertEquals(OcrScript.HI to "declared", OcrScript.resolve(case("hello", "Devanagari")))
    }

    @Test
    fun `substantial second script derives as mixed`() {
        // Real Indian-language books routinely mix scripts; the asset guide calls for a
        // mixed-script subset as its own measurement.
        assertEquals(OcrScript.MIXED, OcrScript.derive("यह एक Computer Science वाक्य है"))
    }

    @Test
    fun `a single stray latin token does not flip a hindi line to mixed`() {
        // Below the 10% minority threshold: one short token inside a long Devanagari line is
        // noise, not a mixed-script line. Classifying it as mixed would drain the hi bucket.
        val text = "यह एक बहुत लंबा हिंदी वाक्य है जिसमें बहुत सारे शब्द हैं और यह जारी रहता है ok"
        assertEquals(OcrScript.HI, OcrScript.derive(text))
    }

    // ── Stratum classification ───────────────────────────────────────────────

    @Test
    fun `untagged is never guessed from the text`() {
        // A stratum describes how the page was produced and laid out. Nothing in the characters
        // reveals whether the page was scanned, so an untagged case must stay untagged rather
        // than be silently bucketed.
        assertEquals(OcrStratum.UNTAGGED, OcrStratum.resolve(case("कोई भी पाठ")))
        assertEquals(OcrStratum.UNTAGGED, OcrStratum.resolve(case("text", stratum = "   ")))
    }

    @Test
    fun `common stratum spellings normalize to canonical buckets`() {
        assertEquals(OcrStratum.SCANNED, OcrStratum.resolve(case("t", stratum = "Scan")))
        assertEquals(OcrStratum.SCANNED, OcrStratum.resolve(case("t", stratum = "NOISY")))
        assertEquals(OcrStratum.TABLES, OcrStratum.resolve(case("t", stratum = "form")))
        assertEquals(OcrStratum.SMALLTEXT, OcrStratum.resolve(case("t", stratum = "footnotes")))
        assertEquals(OcrStratum.MULTICOLUMN, OcrStratum.resolve(case("t", stratum = "multi-column")))
        assertEquals(OcrStratum.CLEAN, OcrStratum.resolve(case("t", stratum = "Clean Digital")))
    }

    @Test
    fun `custom strata pass through so a dataset can add buckets without a code change`() {
        assertEquals("handwritten", OcrStratum.resolve(case("t", stratum = "Handwritten")))
    }

    @Test
    fun `canonical list excludes camera and low light`() {
        // Those belong to the image path, which ADR-0001 keeps on ML Kit. Reporting them here
        // would invite transcribing ground truth for a path this work does not change.
        assertFalse(OcrStratum.CANONICAL.any { it.contains("camera") || it.contains("light") })
        assertEquals(5, OcrStratum.CANONICAL.size)
    }

    @Test
    fun `digits and punctuation alone do not decide script`() {
        // Script-neutral characters appear in both scripts; letting them vote would classify
        // a table of numbers as Latin and pollute the en bucket.
        assertEquals(OcrScript.UNKNOWN, OcrScript.derive("12345 -- 67.89 (100%)"))
        assertEquals(OcrScript.HI, OcrScript.derive("पृष्ठ 12345 -- 67.89"))
    }
}
