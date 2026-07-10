package com.amar.vault.indexing

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Sprint E4 — guards against pathological OCR latency. PURE MATH + thresholds (no Android
 * classes), so every decision is host-testable; [ImageContentExtractor] owns the bitmap work.
 *
 * Root cause these thresholds encode (from the E3 profiler + code audit):
 *  - Tesseract runs at FULL input resolution with no cap and no timeout, as "eng+hin" (two
 *    LSTM passes), while ML Kit downsamples internally. A multi-10-MP camera photo therefore
 *    costs Tesseract minutes (the measured 95s max) where ML Kit stays sub-second.
 *  - All Tesseract calls in the process share one mutex, timed INSIDE the lock — one 95s page
 *    stalls strategy 5 of every concurrently-indexing document (how one slow page became the
 *    13.8-minute document outlier).
 *  - Ensemble strategy 4 blindly 2×-upscales the source: a 12MP photo becomes a 48MP
 *    ARGB_8888 (~192MB) transient plus a second full-size grayscale — pure memory pressure,
 *    while ML Kit's internal downsampling discards the added pixels anyway.
 *
 * Guard semantics (each counted via IndexMetrics; each threshold chosen so that PDF fallback
 * renders — A4 @150dpi ≈ 2.2MP, escalation @300dpi ≈ 8.7MP as ML Kit strategy-4 input only —
 * and ordinary phone screenshots (≤ ~4MP) are NEVER affected):
 *  - [TESS_MAX_MP] = 8MP: Tesseract input above this is downscaled (aspect-preserving) for
 *    the Tesseract pass ONLY; every ML Kit strategy still sees the original pixels. Affects
 *    strategy-5 text only on oversized photos — the exact measured pathology class.
 *  - [TESS_TIMEOUT_MS] = 20s: watchdog interrupt via TessBaseAPI.stop(); recognition returns
 *    the text recognized so far. At p50 ≈ 301ms / p95 ≈ 2.5s (E3), 20s is ≈8× p95 — healthy
 *    pages cannot hit it; only the 95s-class tail does.
 *  - [UPSCALE_BYPASS_SRC_MP] = 4MP: sources at/above this skip strategy 4's 2× upscale (which
 *    would produce a ≥16MP transient) and run the SAME EN inference on the already-shared
 *    grayscale instead. Recognition input differs only in that it lacks a 2× interpolation of
 *    an already-large image — 2× upscaling exists to recover SMALL text on SMALL sources.
 *
 * NOT done in this sprint (per requirements): no strategy removed, no engine replaced, no
 * change on any input below these thresholds — production behaviour there is byte-identical.
 */
object OcrPerfGuards {

    /** Tesseract input cap. 8MP ≈ A4 @300dpi — above any legitimate document render. */
    const val TESS_MAX_MP = 8.0

    /** Watchdog deadline for one Tesseract recognition (engine time, inside the lock). */
    const val TESS_TIMEOUT_MS = 20_000L

    /** Sources at/above this skip the blind 2× upscale (which would create a ≥16MP transient). */
    const val UPSCALE_BYPASS_SRC_MP = 4.0

    fun megapixels(width: Int, height: Int): Double = width.toLong() * height / 1e6

    /**
     * Aspect-preserving target size that brings `width×height` down to ≤ [maxMp] megapixels.
     * Returns null when the input is already within budget (→ use the original, no copy).
     */
    fun downscaleTarget(width: Int, height: Int, maxMp: Double = TESS_MAX_MP): Pair<Int, Int>? {
        val mp = megapixels(width, height)
        if (mp <= maxMp || width <= 0 || height <= 0) return null
        val scale = sqrt(maxMp / mp)
        return max(1, (width * scale).toInt()) to max(1, (height * scale).toInt())
    }

    /** True when strategy 4 should skip the 2× upscale and reuse the shared grayscale. */
    fun shouldBypassUpscale(width: Int, height: Int): Boolean =
        megapixels(width, height) >= UPSCALE_BYPASS_SRC_MP
}
