package com.amar.vault.benchmark

import java.text.Normalizer

/**
 * Pure scoring math for the OCR benchmark module — no Android, no Context, no native libs,
 * so it is unit-testable on any host (mirrors the [OcrAblationMath] split).
 *
 * Two metric families live here and they are deliberately not interchangeable:
 *
 *  - **Accuracy** (`1 − distance / max(len)`, lowercased) — the historical metrics. Kept
 *    byte-comparable with pinned baselines.
 *  - **Error rate** (`distance / len(truth)`, case-sensitive) — CER/WER, the standard OCR
 *    definitions and the unit every NPU-OCR acceptance gate is written in
 *    (`docs/NPU_OCR_ASSET_GUIDE.md` §7).
 *
 * The denominators diverge exactly when the engine over- or under-produces text, which is the
 * failure mode that matters most, so reporting only one of the two hides real regressions.
 */
object OcrScoringMath {

    // ── Normalization ───────────────────────────────────────────────────────────

    /**
     * Composition form must match on both sides or Devanagari scores far below its true accuracy.
     * The production OCR path NFC-normalizes its output; hand-typed ground truth may well be NFD,
     * in which case identical text compares as entirely different characters.
     */
    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Accuracy-metric normalization: NFC + lowercase + collapse whitespace. */
    fun normalize(s: String): String =
        nfc(s).lowercase().replace(WHITESPACE, " ").trim()

    /**
     * Gate normalization for CER/WER: NFC + collapse whitespace, **case preserved**.
     * Case errors are real recognition errors; folding them away would report a cleaner CER
     * than the engine earned and would mask a whole class of Latin regression.
     */
    fun normalizeStrict(s: String): String =
        nfc(s).replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    // ── Accuracy (denominator max(len), lowercased) ─────────────────────────────

    fun characterAccuracy(ocr: String, truth: String): Double {
        val a = normalize(ocr).toList()
        val b = normalize(truth).toList()
        val maxLen = maxOf(a.size, b.size)
        if (maxLen == 0) return 0.0
        return (1.0 - BenchmarkMath.levenshtein(a, b).toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    fun wordAccuracy(ocr: String, truth: String): Double {
        val a = words(normalize(ocr))
        val b = words(normalize(truth))
        val maxLen = maxOf(a.size, b.size)
        if (maxLen == 0) return 0.0
        return (1.0 - BenchmarkMath.levenshtein(a, b).toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    // ── Error rates (denominator len(truth), case-sensitive) ────────────────────

    /**
     * Character error rate, or null when the ground truth normalizes to empty.
     *
     * Null rather than 0.0: CER is undefined with a zero denominator, and 0.0 would read as a
     * perfect score for a case that measured nothing — inflating the average with cases that
     * carry no signal.
     *
     * Deliberately **not** clamped to 1.0. A CER above 1.0 is meaningful: it means the engine
     * emitted more wrong characters than the truth contains, the signature of duplicated or
     * hallucinated text. Clamping would disguise a runaway failure as a merely-bad one.
     */
    fun characterErrorRate(ocr: String, truth: String): Double? {
        val b = normalizeStrict(truth).toList()
        if (b.isEmpty()) return null
        val a = normalizeStrict(ocr).toList()
        return BenchmarkMath.levenshtein(a, b).toDouble() / b.size
    }

    /** Word error rate, or null when the ground truth has no words. See [characterErrorRate]. */
    fun wordErrorRate(ocr: String, truth: String): Double? {
        val b = words(normalizeStrict(truth))
        if (b.isEmpty()) return null
        val a = words(normalizeStrict(ocr))
        return BenchmarkMath.levenshtein(a, b).toDouble() / b.size
    }

    private fun words(s: String): List<String> = s.split(' ').filter { it.isNotEmpty() }
}
