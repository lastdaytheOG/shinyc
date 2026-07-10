package com.amar.vault.benchmark

import com.amar.vault.indexing.OcrPerfGuards

/**
 * Sprint E4 — root-cause classification for OCR latency outliers. PURE: classifies from
 * measured fields only, in strict priority order; when a category's evidence field was not
 * measured for a unit, that category simply cannot fire (never guessed).
 *
 * Category mapping vs the sprint's taxonomy:
 *  - extremely_large_bitmap → measured megapixels ≥ [OcrPerfGuards.TESS_MAX_MP] (the E4
 *    guard threshold — before O1, these were the multi-10-second Tesseract pages).
 *  - timeout_capped → OCR time at/above the watchdog deadline (pre-fix pathological runs,
 *    or post-fix runs that hit the cap).
 *  - dense_text → normal-sized input but very high recognized-character volume (LSTM cost
 *    scales with text lines, not just pixels).
 *  - low_contrast_or_noise → trust score near zero: the page image is noise/garbage that
 *    makes every engine grind (Tesseract worst — it has no confidence early-out here).
 *  - other → none of the measured signals explains it.
 *
 * NOT classifiable from current instrumentation (deliberately absent rather than guessed):
 * language issues, memory pressure, repeated retries — none of these is measured per page
 * today; adding their signals is future instrumentation work, not inference.
 */
object OcrOutlierClassifier {

    const val CAT_TIMEOUT = "timeout_capped"
    const val CAT_LARGE_BITMAP = "extremely_large_bitmap"
    const val CAT_DENSE_TEXT = "dense_text"
    const val CAT_LOW_TRUST = "low_contrast_or_noise"
    const val CAT_OTHER = "other"

    /** Recognized-character volume above which a page counts as dense text. */
    const val DENSE_TEXT_CHARS = 3_000

    /** Trust score below which the page image is effectively noise. */
    const val LOW_TRUST_SCORE = 0.10

    /**
     * @param megapixels input bitmap MP, or null when dimensions were not recorded
     * @param ocrMs measured whole-OCR time for the unit
     * @param trustScore the page's trust-gate score, or null (images have no trust gate)
     * @param mergedChars recognized characters, or null when not recorded
     */
    fun classify(megapixels: Double?, ocrMs: Double, trustScore: Double?, mergedChars: Int?): String = when {
        ocrMs >= OcrPerfGuards.TESS_TIMEOUT_MS -> CAT_TIMEOUT
        megapixels != null && megapixels >= OcrPerfGuards.TESS_MAX_MP -> CAT_LARGE_BITMAP
        mergedChars != null && mergedChars >= DENSE_TEXT_CHARS -> CAT_DENSE_TEXT
        trustScore != null && trustScore < LOW_TRUST_SCORE -> CAT_LOW_TRUST
        else -> CAT_OTHER
    }
}
