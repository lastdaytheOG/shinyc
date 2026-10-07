package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.amar.vault.IndexMetrics
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Image content-extraction stage (image/OCR path).
 *
 * Owns the complete image → text pipeline that previously lived inline in
 * `IndexingPipeline.indexBitmap`: the OCR engine resources (ML Kit Latin + Devanagari, Tesseract),
 * the 5-strategy aggressive OCR ensemble, image preprocessing (high-contrast grayscale, invert),
 * QR/barcode scanning, and OCR result merging / line-level normalization.
 *
 * Concrete (no interface — single implementation, no polymorphism), per the standing rule. Every
 * algorithm here is a **verbatim** move from the prior inline code: identical strategy set and
 * ordering, identical preprocessing matrices and sequence, identical merge/normalization, identical
 * QR behaviour. No algorithm changes.
 *
 * The orchestrator keeps only the coordination it always had — it calls [extract], which runs OCR
 * and QR concurrently exactly as `indexBitmap` did, and returns both results.
 */
class ImageContentExtractor(private val context: Context) {

    companion object {
        // Sprint P4 — tier-1 acceptance floor for the PDF escalation ladder. Pages
        // with fewer recognized words (title pages, sparse forms) always escalate:
        // correctness over speed.
        private const val PDF_TIER1_MIN_WORDS = 20

        // Sprint P3 — the ColorMatrix math is constant, and ColorMatrixColorFilter is
        // immutable + safe to share across threads/Paints, so both filters are built
        // once instead of per preprocessing call. Matrix values are IDENTICAL to the
        // previous inline construction (saturation 0, contrast 1.5, offset −64).
        private val GRAYSCALE_CONTRAST_FILTER: ColorMatrixColorFilter = run {
            val grayMatrix = ColorMatrix()
            grayMatrix.setSaturation(0f)
            val contrast = 1.5f
            val offset = (-128f * contrast) + 128f
            val contrastMatrix = ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            ))
            grayMatrix.postConcat(contrastMatrix)
            ColorMatrixColorFilter(grayMatrix)
        }
        private val INVERT_FILTER = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )))

        /** Round-to-nearest ms for sub-millisecond stages (merge/NFC would floor to 0). */
        private fun nsToMs(ns: Long): Long = (ns + 500_000) / 1_000_000
    }

    private val mlKitEn = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val mlKitHi = TextRecognition.getClient(
        DevanagariTextRecognizerOptions.Builder().build()
    )
    // Sprint P1 — one client for the extractor's lifetime instead of a
    // construct/close per scanned bitmap. The client is stateless per process.
    private val barcodeScanner by lazy {
        com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
    }

    /** OCR text + QR payloads extracted from a single bitmap. */
    data class ImageContent(val ocrText: String, val qrPayloads: List<String>)

    /**
     * Run OCR and QR/barcode scanning concurrently and return both — the same parallel
     * dispatch `indexBitmap` performed inline (OCR on the inherited dispatcher, QR on IO).
     *
     * [sourceId] (Sprint E1) is used only to label the OCR strategy-contribution report when
     * instrumentation is enabled; it has no effect on the returned [ImageContent]. When
     * instrumentation is disabled (production default) [recorder] is null and every timing
     * wrapper below is a pure pass-through — output is byte-identical to the uninstrumented path.
     */
    suspend fun extract(bitmap: Bitmap, sourceId: String? = null): ImageContent {
        // Production Evaluation Mode: a real indexing pass is captured only when the dev
        // toggle is on (recorderOrNull() → non-null). Off = null recorder = pure pass-through.
        val (content, report) = extractInternal(bitmap, sourceId, OcrInstrumentation.recorderOrNull())
        report?.let { OcrInstrumentation.publish(it) }
        return content
    }

    /**
     * Benchmark-only variant: ALWAYS instruments and returns the report to the caller, and
     * NEVER publishes to the production capture buffer — so a GoldenDataset run cannot pollute
     * or clear the samples collected from real indexing. Output is byte-identical to [extract].
     *
     * Sprint E2: a caller may supply its own [recorder] to read the per-strategy raw texts back
     * afterwards ([OcrStrategyRecorder.runsSnapshot]) — the ablation benchmark needs them to
     * replay leave-one-out merges. The default preserves the E1 behaviour exactly.
     */
    suspend fun extractInstrumented(
        bitmap: Bitmap,
        sourceId: String? = null,
        recorder: OcrStrategyRecorder = OcrStrategyRecorder(),
    ): Pair<ImageContent, OcrImageReport?> =
        extractInternal(bitmap, sourceId, recorder)

    private suspend fun extractInternal(
        bitmap: Bitmap,
        sourceId: String?,
        recorder: OcrStrategyRecorder?,
    ): Pair<ImageContent, OcrImageReport?> = coroutineScope {
        // Sprint E4 — record input geometry + whole-OCR wall time (observation only).
        recorder?.setInput(bitmap.width, bitmap.height)
        val tOcr = System.currentTimeMillis()
        val ocrDeferred = async { runAggressiveOcr(bitmap, recorder) }
        val qrDeferred = async(Dispatchers.IO) { scanBarcode(bitmap) }
        val mergedOcr = ocrDeferred.await()
        recorder?.setTotalMs((System.currentTimeMillis() - tOcr).toDouble())
        val qr = qrDeferred.await()
        // Observation only: attribute the (already-final) merged text to its source strategies.
        val report = recorder?.buildReport(sourceId ?: "img@${System.identityHashCode(bitmap)}", mergedOcr)
        // Sprint 4A: NFC at the OCR ingestion boundary — one site covers screenshots,
        // images AND the PDF trust-gate fallback. ML Kit output is NFC in practice, so
        // this is a no-op for existing behaviour; it guarantees canonical bytes in Room.
        ImageContent(normalize(mergedOcr), qr) to report
    }

    /** Sprint E1 — time one strategy's work into [recorder]; pure pass-through when null. */
    private suspend fun timedStrategy(
        recorder: OcrStrategyRecorder?,
        strategy: OcrStrategy,
        block: suspend () -> String,
    ): String {
        if (recorder == null) return block()
        val t0 = System.nanoTime()
        val out = block()
        recorder.record(strategy, out, (System.nanoTime() - t0) / 1_000_000.0)
        return out
    }

    /** Sprint E1 — time one preprocessing conversion into [recorder]; pure pass-through when null. */
    private inline fun <T> timedPre(recorder: OcrStrategyRecorder?, kind: String, block: () -> T): T {
        if (recorder == null) return block()
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            recorder.pre(kind, (System.nanoTime() - t0) / 1_000_000.0)
        }
    }

    /**
     * Sprint P4 — escalation-ladder OCR for RENDERED PDF PAGES ONLY (never for
     * photos/screenshots, whose full ensemble is untouched).
     *
     * Rendered pages are synthetic, axis-aligned, typically dark-on-light images,
     * so the raw-image pass alone usually recovers everything the 7-pass ensemble
     * would. Ladder:
     *
     *   Tier 1: ML Kit EN + HI on the raw render — VERBATIM ensemble strategy 1.
     *           Accepted iff the output has ≥ [PDF_TIER1_MIN_WORDS] words AND
     *           passes [TextTrustScorer] (the same gate that judged the stripper
     *           text — garbage, mojibake and symbol soup all force escalation).
     *   Tier 2: the FULL classic ensemble, reusing the tier-1 result as its
     *           strategy-1 output (no duplicate passes). Worst case = pre-P4
     *           behaviour exactly. [renderHiRes] (Sprint P4, item #2): when the
     *           caller can re-render the page at 300dpi, that sharp vector render
     *           replaces the blurry 2× raster upscale as strategy 4's input.
     *
     * Acceptance/escalation are counted (PDF_OCR_TIER1_ACCEPTED / PDF_OCR_ESCALATED)
     * — the validation signal for the golden-set eval this ladder is gated on.
     */
    suspend fun extractPdfPageText(
        bitmap: Bitmap,
        renderHiRes: (suspend () -> Bitmap?)? = null,
        sourceId: String? = null,
    ): String {
        // Production Evaluation Mode: a rendered PDF fallback page feeds the SAME instrumentation
        // as screenshots/images. recorder is null unless the dev capture toggle is on → then every
        // wrapper below is a pure pass-through and output is byte-identical to the uninstrumented path.
        val recorder = OcrInstrumentation.recorderOrNull()
        // Sprint E4 — input geometry + whole-ladder wall time (observation only).
        recorder?.setInput(bitmap.width, bitmap.height)

        // NPU-OCR step 4 — dev-only C1 calibration capture. No-op unless the capture toggle is on.
        // Deliberately BEFORE tLadder: writing a PNG costs real milliseconds, and folding that into
        // the ladder's measured time would corrupt every OCR latency metric during a capture run.
        // Captures the bitmap exactly as received, before any preprocessing — that is the tensor a
        // future NPU detector would be fed, and calibration is only valid on the real distribution.
        OcrPageCapture.capture(context, bitmap, sourceId)

        val tLadder = System.currentTimeMillis()

        if (AdaptivePdfOcrControl.mode == AdaptivePdfOcrControl.Mode.LEGACY_ENSEMBLE) {
            val merged = runEnsemble(bitmap, precomputedRaw = null, renderHiRes = renderHiRes, recorder = recorder)
            recorder?.let {
                it.setTotalMs((System.currentTimeMillis() - tLadder).toDouble())
                OcrInstrumentation.publish(
                    it.buildReport(sourceId ?: "pdfpage@${System.identityHashCode(bitmap)}", merged)
                )
            }
            return normalize(merged)
        }

        // Tier 1 — same inputs, merge and RAW-strategy semantics as ensemble strategy 1.
        // The EN and HI passes are independent models reading the SAME immutable InputImage, so
        // they run CONCURRENTLY instead of EN-then-HI. Every accepted bilingual page pays for both
        // (routing to one would drop Devanagari on mixed eng+hin pages), so overlapping them turns
        // the hot-path cost from EN+HI into max(EN, HI). Output is byte-identical: mergeTexts is a
        // pure function of the two final strings, independent of which finished first. No new
        // thread-safety surface — runEnsemble already calls `recognize` from parallel strategies,
        // so IndexMetrics + recorder are already exercised concurrently.
        val tierStart = System.nanoTime()
        val image = InputImage.fromBitmap(bitmap, 0)
        val (en, hi) = coroutineScope {
            val enDeferred = async { recognize(mlKitEn, image, IndexMetrics.Timing.OCR_MLKIT_EN, recorder) }
            val hiDeferred = async { recognize(mlKitHi, image, IndexMetrics.Timing.OCR_MLKIT_HI, recorder) }
            enDeferred.await() to hiDeferred.await()
        }
        val tier1 = mergeTexts(en, hi)
        // Record tier-1 as strategy RAW with its real latency (runEnsemble will NOT overwrite it
        // on escalation, because precomputedRaw short-circuits its RAW pass — see the guard below).
        // With the two passes overlapped, this wall time is now max(EN, HI), not EN+HI.
        recorder?.record(OcrStrategy.RAW, tier1, (System.nanoTime() - tierStart) / 1_000_000.0)

        val tier1Final = normalize(mergeAllOcrResults(listOf(tier1)))
        if (tier1Healthy(tier1Final)) {
            IndexMetrics.increment(IndexMetrics.Event.PDF_OCR_TIER1_ACCEPTED)
            // Sprint E3 — observation only: attribute the ladder outcome to the current page.
            // Sprint P5 — route by OcrPageTag so concurrent fallback pages attribute correctly.
            kotlin.coroutines.coroutineContext[DocProfileRecorder]?.ocrTierOutcome(
                accepted = true, pageNum = kotlin.coroutines.coroutineContext[OcrPageTag]?.page,
            )
            recorder?.let {
                it.setTotalMs((System.currentTimeMillis() - tLadder).toDouble())
                OcrInstrumentation.publish(
                    it.buildReport(sourceId ?: "pdfpage@${System.identityHashCode(bitmap)}", mergeAllOcrResults(listOf(tier1)))
                )
            }
            return tier1Final
        }
        IndexMetrics.increment(IndexMetrics.Event.PDF_OCR_ESCALATED)
        // Sprint E3 — observation only: attribute the ladder outcome to the current page.
        // Sprint P5 — route by OcrPageTag so concurrent fallback pages attribute correctly.
        kotlin.coroutines.coroutineContext[DocProfileRecorder]?.ocrTierOutcome(
            accepted = false, pageNum = kotlin.coroutines.coroutineContext[OcrPageTag]?.page,
        )
        val merged = runEnsemble(bitmap, precomputedRaw = tier1, renderHiRes = renderHiRes, recorder = recorder)
        recorder?.let {
            it.setTotalMs((System.currentTimeMillis() - tLadder).toDouble())
            OcrInstrumentation.publish(
                it.buildReport(sourceId ?: "pdfpage@${System.identityHashCode(bitmap)}", merged)
            )
        }
        return normalize(merged)
    }

    /**
     * Conservative acceptance gate: prefer a wasted escalation over a missed one.
     * Judges only what tier 1 produced — it cannot see text tier 1 missed entirely,
     * which is why the threshold is deliberately strict and the ladder is validated
     * against the golden dataset before being trusted.
     */
    private fun tier1Healthy(text: String): Boolean {
        if (text.isBlank()) return false
        var words = 0
        var inWord = false
        for (ch in text) {
            if (ch.isWhitespace()) inWord = false
            else if (!inWord) { words++; inWord = true }
        }
        if (words < PDF_TIER1_MIN_WORDS) return false
        return TextTrustScorer.score(text).trusted
    }

    // ── Sprint P3 instrumentation helpers (timing only — no behaviour change) ──

    private fun normalize(text: String): String {
        val t0 = System.nanoTime()
        try {
            return com.amar.vault.UnicodeText.nfc(text)
        } finally {
            com.amar.vault.IndexMetrics.recordDuration(
                com.amar.vault.IndexMetrics.Timing.OCR_NFC, nsToMs(System.nanoTime() - t0)
            )
        }
    }

    /** Times one ML Kit inference; call order and inputs are exactly the prior inline calls. */
    private suspend fun recognize(
        client: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage,
        metric: String,
        recorder: OcrStrategyRecorder? = null,
    ): String {
        val t0 = System.currentTimeMillis()
        try {
            return client.process(image).await().text.trim()
        } finally {
            val elapsed = System.currentTimeMillis() - t0
            com.amar.vault.IndexMetrics.recordDuration(metric, elapsed)
            recorder?.engine("mlkit", elapsed.toDouble())
        }
    }

    /** Times one preprocessing bitmap conversion. */
    private inline fun <T> preprocess(block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            com.amar.vault.IndexMetrics.recordDuration(
                com.amar.vault.IndexMetrics.Timing.OCR_PREPROCESS, nsToMs(System.nanoTime() - t0)
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Aggressive multi-pass OCR
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Runs OCR with 5 strategies in parallel, merges all unique text found.
     *
     * Why: a single MLKit pass on the raw image misses:
     * - Stylized product fonts ("Good Day", "Mango Masti")
     * - Colored text on colored backgrounds
     * - Small text on packaging
     * - Curved/rotated text
     *
     * Each preprocessing variant makes different text visible to the OCR engine.
     * We run them all and merge unique lines.
     */
    private suspend fun runAggressiveOcr(bitmap: Bitmap, recorder: OcrStrategyRecorder? = null): String =
        runEnsemble(bitmap, precomputedRaw = null, renderHiRes = null, recorder = recorder)

    /**
     * The classic 5-strategy ensemble. [precomputedRaw] (Sprint P4): a caller that has
     * ALREADY run strategy 1 (the escalation ladder's tier 1) passes its result so it is
     * reused verbatim instead of re-run — same merge position, no duplicate inference.
     * [renderHiRes] (Sprint P4): when provided (PDF pages), strategy 4 consumes a sharp
     * 300dpi vector re-render instead of the blurry 2× raster upscale; on null or render
     * failure it falls back to the legacy upscale, so worst case is the pre-P4 pipeline.
     * With both parameters null, behaviour is byte-identical to the pre-P4 ensemble
     * (the image/screenshot path always calls it that way).
     */
    private suspend fun runEnsemble(
        bitmap: Bitmap,
        precomputedRaw: String?,
        renderHiRes: (suspend () -> Bitmap?)?,
        recorder: OcrStrategyRecorder? = null,
    ): String {
        // Shared preprocessed input: strategies 2 (ML Kit) and 5 (Tesseract) previously each
        // rendered their own IDENTICAL high-contrast grayscale of the source bitmap. One
        // bitmap is now rendered once and read concurrently by both (reads only — neither
        // engine mutates its input). Recycled after the coroutineScope completes, which
        // structured concurrency guarantees is after ALL strategies have finished (or been
        // fully cancelled) — so the recycle can never race an in-flight OCR pass.
        val sharedGray = timedPre(recorder, "grayscale") { preprocess { toHighContrastGrayscale(bitmap) } }
        try {
            return coroutineScope {
                // ── Strategy 1: Raw image (works for clean screenshots) ──────────
                val rawDeferred = async(Dispatchers.IO) {
                    // When precomputedRaw is supplied (PDF escalation), the caller already ran and
                    // TIMED tier 1 as RAW — reuse it verbatim and do NOT re-record (which would
                    // overwrite the real tier-1 latency with a ~0ms no-op). Image path: unchanged.
                    if (precomputedRaw != null) {
                        precomputedRaw
                    } else {
                        timedStrategy(recorder, OcrStrategy.RAW) {
                            val image = InputImage.fromBitmap(bitmap, 0)
                            val en = recognize(mlKitEn, image, IndexMetrics.Timing.OCR_MLKIT_EN, recorder)
                            val hi = recognize(mlKitHi, image, IndexMetrics.Timing.OCR_MLKIT_HI, recorder)
                            mergeTexts(en, hi)
                        }
                    }
                }

                // ── Strategy 2: High-contrast grayscale (shared bitmap) ─────────
                //    Strips color → reveals text hidden by colorful backgrounds
                val grayDeferred = async(Dispatchers.IO) {
                    timedStrategy(recorder, OcrStrategy.GRAYSCALE) {
                        val image = InputImage.fromBitmap(sharedGray, 0)
                        val en = recognize(mlKitEn, image, IndexMetrics.Timing.OCR_MLKIT_EN, recorder)
                        val hi = recognize(mlKitHi, image, IndexMetrics.Timing.OCR_MLKIT_HI, recorder)
                        mergeTexts(en, hi)
                    }
                }

                // ── Strategy 3: Inverted (white text on dark backgrounds) ───────
                val invertDeferred = async(Dispatchers.IO) {
                    timedStrategy(recorder, OcrStrategy.INVERT) {
                        val inv = timedPre(recorder, "invert") { preprocess { invertBitmap(bitmap) } }
                        try {
                            val image = InputImage.fromBitmap(inv, 0)
                            recognize(mlKitEn, image, IndexMetrics.Timing.OCR_MLKIT_EN, recorder)
                        } finally {
                            inv.recycle()
                        }
                    }
                }

                // ── Strategy 4: High-resolution pass (small text) ───────────────
                //    Images/screenshots: 2× raster upscale (legacy, unchanged).
                //    PDF escalation: fresh 300dpi vector render — same pixel count
                //    as the 2× upscale but sharp instead of interpolated.
                val upscaleDeferred = async(Dispatchers.IO) {
                    timedStrategy(recorder, OcrStrategy.UPSCALE) {
                        val hiRes = renderHiRes?.let { render -> runCatching { render() }.getOrNull() }
                        // Sprint E4 (O3) — a blind 2× upscale of an already-large source
                        // (≥4MP → ≥16MP ARGB transient + a second full-size grayscale) adds
                        // no recognizable detail: the 2× pass exists to recover SMALL text on
                        // SMALL sources, and ML Kit downsamples big inputs internally anyway.
                        // Run the same EN inference on the existing shared grayscale instead
                        // — identical pixels minus a 2× interpolation. Never applies to the
                        // PDF hi-res vector render (hiRes != null) or to sources < 4MP
                        // (screenshots, PDF 150dpi renders), which keep the legacy path
                        // byte-identical. Counted → optimization hit rate.
                        if (hiRes == null && OcrPerfGuards.shouldBypassUpscale(bitmap.width, bitmap.height)) {
                            IndexMetrics.increment(IndexMetrics.Event.OCR_UPSCALE_BYPASSED)
                            val image = InputImage.fromBitmap(sharedGray, 0)
                            recognize(mlKitEn, image, IndexMetrics.Timing.OCR_MLKIT_EN, recorder)
                        } else {
                            val src = hiRes
                                ?: timedPre(recorder, "upscale") {
                                    preprocess {
                                        Bitmap.createScaledBitmap(
                                            bitmap,
                                            bitmap.width * 2,
                                            bitmap.height * 2,
                                            true
                                        )
                                    }
                                }
                            try {
                                val gray = timedPre(recorder, "grayscale") { preprocess { toHighContrastGrayscale(src) } }
                                try {
                                    val image = InputImage.fromBitmap(gray, 0)
                                    recognize(mlKitEn, image, IndexMetrics.Timing.OCR_MLKIT_EN, recorder)
                                } finally {
                                    gray.recycle()
                                }
                            } finally {
                                src.recycle()
                            }
                        }
                    }
                }

                // ── Strategy 5: Tesseract on preprocessed image (shared bitmap) ─
                //    Tesseract sometimes catches what MLKit misses on noisy images
                val tessDeferred = async(Dispatchers.IO) {
                    timedStrategy(recorder, OcrStrategy.TESSERACT) { runTesseract(sharedGray, recorder).trim() }
                }

                // ── Merge all results ───────────────────────────────────────────
                val results = listOf(
                    rawDeferred.await(),
                    grayDeferred.await(),
                    invertDeferred.await(),
                    upscaleDeferred.await(),
                    tessDeferred.await()
                )

                val tMerge = System.nanoTime()
                val merged = mergeAllOcrResults(results)
                val mergeNs = System.nanoTime() - tMerge
                IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_MERGE, nsToMs(mergeNs))
                recorder?.setMergeMs(mergeNs / 1e6)
                android.util.Log.d("OCR", "Strategies produced: ${results.map { it.length }} chars → merged: ${merged.length}")
                merged
            }
        } finally {
            sharedGray.recycle()
        }
    }

    /**
     * Merges OCR results from multiple passes.
     * Deduplicates at the line level — keeps unique lines from all strategies.
     * Returns the combined text sorted by line length (longest first = most info).
     */
    private fun mergeAllOcrResults(results: List<String>): String {
        val seenLines = mutableSetOf<String>()
        val uniqueLines = mutableListOf<String>()

        for (text in results) {
            if (text.isBlank()) continue
            for (line in text.split("\n")) {
                val cleaned = line.trim()
                if (cleaned.isEmpty()) continue
                val normalized = cleaned.lowercase().replace(Regex("\\s+"), " ")
                // Skip if we already have a line that contains this one (substring dedup)
                if (seenLines.any { it.contains(normalized) }) continue
                // Remove lines that are substrings of this new line
                seenLines.removeAll { normalized.contains(it) }
                seenLines.add(normalized)
                uniqueLines.add(cleaned)
            }
        }

        return uniqueLines.joinToString("\n")
    }

    /** Merges English and Hindi OCR results, picking the longer one or combining if both substantial */
    private fun mergeTexts(en: String, hi: String): String {
        if (en.isBlank()) return hi
        if (hi.isBlank()) return en
        if (en.length > hi.length * 2) return en
        if (hi.length > en.length * 2) return hi
        // Both substantial — combine unique lines
        val enLines = en.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val hiLines = hi.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val combined = (enLines + hiLines).distinct()
        return combined.joinToString("\n")
    }

    // ════════════════════════════════════════════════════════════════════════
    // Image preprocessing
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Converts to grayscale with boosted contrast.
     * Uses a ColorMatrix that:
     * 1. Converts to grayscale (removes color that confuses OCR)
     * 2. Increases contrast by 1.5× (makes faint text readable)
     * 3. Shifts brightness slightly to avoid washing out
     */
    private fun toHighContrastGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        // Grayscale + 1.5× contrast — the precomputed shared filter (identical matrix).
        paint.colorFilter = GRAYSCALE_CONTRAST_FILTER
        canvas.drawBitmap(src, 0f, 0f, paint)

        return result
    }

    /**
     * Inverts colors — catches white/light text on dark backgrounds
     * that MLKit completely misses on the original.
     */
    private fun invertBitmap(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        paint.colorFilter = INVERT_FILTER
        canvas.drawBitmap(src, 0f, 0f, paint)

        return result
    }

    // ════════════════════════════════════════════════════════════════════════
    // QR / Barcode scanning
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Scans the bitmap for QR codes, barcodes, and other 2D codes.
     * Returns a list of decoded payloads (URLs, UPI strings, plain text).
     *
     * Uses MLKit BarcodeScanning — detects ALL barcode formats.
     * Only uses [rawValue] which is available on all MLKit barcode versions.
     */
    private suspend fun scanBarcode(bitmap: Bitmap): List<String> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = barcodeScanner.process(image).await()

            barcodes.mapNotNull { barcode ->
                barcode.rawValue
            }.filter { it.isNotBlank() }.distinct()
        } catch (e: Exception) {
            android.util.Log.e("IndexingPipeline", "Barcode scan failed: " + e.message)
            emptyList()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tesseract engine
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun runTesseract(bitmap: Bitmap, recorder: OcrStrategyRecorder? = null): String {
        // Sprint E4 — queue wait measured OUTSIDE the lock. OCR_TESSERACT is engine time only,
        // so mutex convoying (one slow page stalling every other document's strategy 5) was
        // previously invisible. This makes the serialization amplification measurable.
        val tQueue = System.currentTimeMillis()
        return TesseractRuntime.withApi(context) { tessApi ->
            IndexMetrics.recordDuration(
                IndexMetrics.Timing.OCR_TESSERACT_QUEUE, System.currentTimeMillis() - tQueue
            )
            // Sprint E4 (O1) — Tesseract input cap. Tesseract runs full-resolution LSTM line
            // scans (×2 for "eng+hin") with no internal downsampling — the measured 95s pages.
            // Inputs above TESS_MAX_MP (8MP; PDF fallback renders are ~2.2MP and never hit
            // this) are downscaled aspect-preserving FOR THIS PASS ONLY; all ML Kit strategies
            // still see the original pixels. Counted → optimization hit rate.
            val target = OcrPerfGuards.downscaleTarget(bitmap.width, bitmap.height)
            val input = if (target == null) bitmap else {
                IndexMetrics.increment(IndexMetrics.Event.OCR_TESS_INPUT_DOWNSCALED)
                Bitmap.createScaledBitmap(bitmap, target.first, target.second, true)
            }
            try {
                // Timed inside the lock — engine time, not queue wait.
                val t0 = System.currentTimeMillis()
                tessApi.setImage(input)
                // Sprint E4 (O2) — watchdog. TessBaseAPI.stop() interrupts the in-flight
                // recognition, making getUTF8Text return the text recognized so far. At 20s
                // (≈8× the measured p95) no healthy page can hit this; only the pathological
                // tail does. The done-flag closes the (microsecond) window where a recognition
                // completes exactly at the deadline — stop() must never fire while idle.
                val text = coroutineScope {
                    val done = AtomicBoolean(false)
                    val watchdog = launch(Dispatchers.IO) {
                        delay(OcrPerfGuards.TESS_TIMEOUT_MS)
                        if (!done.get()) {
                            IndexMetrics.increment(IndexMetrics.Event.OCR_TESS_TIMEOUT)
                            runCatching { tessApi.stop() }
                        }
                    }
                    try {
                        tessApi.utF8Text ?: ""
                    } finally {
                        done.set(true)
                        watchdog.cancel()
                    }
                }
                val elapsed = System.currentTimeMillis() - t0
                IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_TESSERACT, elapsed)
                recorder?.engine("tesseract", elapsed.toDouble())
                tessApi.clear()
                text
            } catch (e: Exception) {
                e.printStackTrace()
                try { tessApi.clear() } catch (_: Exception) {}
                ""
            } finally {
                if (input !== bitmap) input.recycle()
            }
        }
    }

}
