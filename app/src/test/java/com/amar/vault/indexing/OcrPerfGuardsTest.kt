package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Sprint E4 — pure-JVM tests for the pathological-OCR guard math. The central safety
 * property: NO guard may ever fire on the pipeline's legitimate inputs (PDF fallback
 * renders, ordinary screenshots) — production behaviour there must be byte-identical.
 */
class OcrPerfGuardsTest {

    // ── Safety envelope: legitimate inputs are NEVER touched ─────────────────

    @Test
    fun `PDF 150dpi A4 render is untouched by every guard`() {
        // The exact render geometry the trust-gate fallback produces (A4 @150dpi).
        val w = 1240; val h = 1754 // ≈2.2MP
        assertNull(OcrPerfGuards.downscaleTarget(w, h))
        assertFalse(OcrPerfGuards.shouldBypassUpscale(w, h))
    }

    @Test
    fun `PDF 300dpi escalation render is untouched by the Tesseract cap`() {
        // 300dpi A4 ≈ 8.7MP is ML Kit strategy-4 input only — Tesseract always gets the
        // 150dpi shared grayscale on the PDF path, so the cap must clear A4@150dpi with
        // huge margin (asserted above); this asserts the *cap boundary* itself.
        assertNull(OcrPerfGuards.downscaleTarget(2000, 4000)) // 8.0MP exactly → within budget
    }

    @Test
    fun `typical phone screenshot is untouched`() {
        val w = 1080; val h = 2400 // 2.6MP
        assertNull(OcrPerfGuards.downscaleTarget(w, h))
        assertFalse(OcrPerfGuards.shouldBypassUpscale(w, h))
    }

    // ── O1: Tesseract input cap ───────────────────────────────────────────────

    @Test
    fun `oversized photo is downscaled to the megapixel budget preserving aspect`() {
        val w = 6000; val h = 8000 // 48MP camera photo
        val (tw, th) = OcrPerfGuards.downscaleTarget(w, h)!!
        val mp = OcrPerfGuards.megapixels(tw, th)
        assertTrue("target $tw x $th = ${mp}MP exceeds budget", mp <= OcrPerfGuards.TESS_MAX_MP)
        assertTrue("budget wasted: only ${mp}MP", mp > OcrPerfGuards.TESS_MAX_MP * 0.9)
        val srcAspect = w.toDouble() / h
        val dstAspect = tw.toDouble() / th
        assertTrue("aspect drift: $srcAspect vs $dstAspect", abs(srcAspect - dstAspect) < 0.01)
    }

    @Test
    fun `megapixels does not overflow on absurd dimensions`() {
        // 100_000 × 100_000 would overflow Int multiplication (1e10 > 2^31).
        assertEquals(10_000.0, OcrPerfGuards.megapixels(100_000, 100_000), 1e-6)
    }

    @Test
    fun `degenerate dimensions return null instead of a broken target`() {
        assertNull(OcrPerfGuards.downscaleTarget(0, 5000))
        assertNull(OcrPerfGuards.downscaleTarget(5000, 0))
    }

    // ── O3: upscale bypass boundary ───────────────────────────────────────────

    @Test
    fun `upscale bypass fires exactly at the source threshold`() {
        assertFalse(OcrPerfGuards.shouldBypassUpscale(1999, 2000)) // 3.998MP
        assertTrue(OcrPerfGuards.shouldBypassUpscale(2000, 2000))  // 4.0MP
        assertTrue(OcrPerfGuards.shouldBypassUpscale(4000, 3000))  // 12MP photo
    }

    // ── Threshold sanity (documents the measured-data rationale) ─────────────

    @Test
    fun `watchdog deadline is far above the measured healthy tail`() {
        // E3 measured Tesseract p95 ≈ 2.5s; the watchdog must only ever cut the
        // pathological tail (measured max ≈ 95s), never a healthy page.
        assertTrue(OcrPerfGuards.TESS_TIMEOUT_MS >= 8 * 2_500L)
    }
}
