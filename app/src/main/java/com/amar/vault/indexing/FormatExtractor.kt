package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.amar.vault.IndexMetrics
import com.amar.vault.UnicodeText
import com.amar.vault.VaultLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.InputStream

/** One extracted, chunked unit of a document. Moved verbatim from DocumentIndexer. */
data class PagedChunk(val text: String, val pdfPage: Int?, val chunkIndex: Int)

/**
 * A single document-format handler — the validated extensibility point.
 *
 * Adding a future format = add one `FormatExtractor` to [DocumentFormatRegistry.DEFAULT];
 * `DocumentIndexer` and `DocumentContentExtractor` are untouched. No reflection, no runtime
 * plugin system — a plain static list.
 *
 * Extractors are stateless (context is passed per call), so they can be shared singletons.
 * Each owns its own parsing algorithm, verbatim from the prior `DocumentIndexer` methods.
 */
interface FormatExtractor {
    val mimeTypes: Set<String>
    /**
     * File-name extensions of this format, lowercase and without the dot. They identify a file
     * whose provider gave no usable type ("application/octet-stream", or none at all).
     */
    val extensions: Set<String>
    /** Family tag prepended to generated tags (was DocFamily.tag). */
    val tag: String
    /** VaultItem.itemType for chunks of this family (was DocFamily.itemType). */
    val itemType: String
    /** Suspend since Sprint 4A: the PDF extractor may run the (suspend) OCR fallback. */
    suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk>

    /**
     * Sprint P6 — progressive variant. Emits chunks in batches AS THEY ARE PRODUCED so the caller
     * can commit them to the index incrementally (the document becomes searchable page-by-page
     * instead of only after the whole file is parsed). [onBatch] is invoked sequentially; the
     * concatenation of all batches, in call order, equals [extract]'s result.
     *
     * Default: one batch = the full [extract] result — identical externally-visible output, no
     * incrementality. Overridden by [PdfFormatExtractor] to emit one batch per page. Whole-document
     * formats (Word/Excel/EPUB) have nothing to stream and keep the default.
     */
    suspend fun extractStreaming(
        context: Context,
        uri: Uri,
        chunker: Chunker,
        onBatch: suspend (List<PagedChunk>) -> Unit,
    ) {
        val all = extract(context, uri, chunker)
        if (all.isNotEmpty()) onBatch(all)
    }
}

internal fun openDocStream(context: Context, uri: Uri): InputStream =
    context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")

/**
 * PDF — page-by-page text extraction, chunked per page (preserves pdfPage numbers).
 *
 * Sprint 4A — per-page text-trust gate (implements the approved architecture review):
 *
 *   stripper text → NFC → [TextTrustScorer] → trusted?
 *     ├─ yes: continue exactly as before (chunk the stripper text)
 *     └─ no:  render THIS page only (PDFBox [PDFRenderer], honors /Rotate) →
 *             the existing [ImageContentExtractor] OCR ensemble → chunk the OCR text
 *
 * Healthy English/Hindi digital pages pass the gate and never touch a bitmap; the
 * OCR engine and the page renderer are built lazily, only when a page actually fails.
 * Pages are processed sequentially and each rendered bitmap is recycled immediately —
 * bounded memory, no retention. The same open [PDDocument] serves both the stripper
 * and the renderer (no duplicate parsing).
 */
class PdfFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/pdf")
    override val extensions = setOf("pdf")
    override val tag = "pdf document"
    override val itemType = "pdf"

    private companion object {
        const val TAG = "PdfTrustGate"
        /** ML Kit sweet spot vs. RAM: A4 @150dpi ≈ 1240×1754 RGB, recycled per page. */
        const val OCR_RENDER_DPI = 150f
        /**
         * Sprint P4 — escalation-only re-render. Same pixel count as the ensemble's
         * legacy 2× upscale of the 150dpi render, but rendered sharp from the vector
         * source instead of interpolated from raster. Used only when tier 1 escalates.
         */
        const val ESCALATION_RENDER_DPI = 300f

        /**
         * Process-wide ceiling on concurrent OCR fallback pages. Per-document concurrency is
         * [AdaptivePdfOcrControl.pdfOcrConcurrency] (1 = the legacy strictly-serial path), clamped
         * to this ceiling. Memory cost of concurrency = N live render bitmaps (150dpi ≈ 2.2MP) +
         * N ML Kit working sets; the native Tesseract runtime stays serialized by its OWN mutex
         * regardless, so escalation pages never multiply Tesseract memory. PDF text stripping is
         * unaffected — a digital PDF still extracts without ever taking this permit.
         */
        const val MAX_OCR_CONCURRENCY = 4
        val OCR_FALLBACK_PERMIT = Semaphore(MAX_OCR_CONCURRENCY)

        /** Round-to-nearest ms — per-doc nano accumulators would floor to 0 with integer division. */
        fun nsToMs(ns: Long): Long = (ns + 500_000) / 1_000_000
    }

    /**
     * One rendered fallback page handed from the strip/render producer to the OCR
     * consumer. The consumer owns (and recycles) [bitmap]; [strippedFallback] is the
     * NFC'd stripper text used verbatim when OCR fails — same semantics as the
     * pre-P3 serial catch branch. [renderHiRes] (Sprint P4) re-renders this page at
     * [ESCALATION_RENDER_DPI] for the ensemble's strategy 4, serialized against the
     * stripper via the document mutex (PDFBox allows no concurrent access to one doc).
     */
    private class OcrJob(
        val page: Int,
        val bitmap: Bitmap,
        val strippedFallback: String,
        val renderHiRes: suspend () -> Bitmap?,
    )

    // THE existing OCR engine, reused — created lazily so PDFs whose every page passes
    // the gate (all healthy digital PDFs) never construct it. One instance for the whole
    // document path (this extractor is a shared singleton via DocumentFormatRegistry.DEFAULT).
    @Volatile
    private var ocrEngine: ImageContentExtractor? = null

    private fun ocrEngine(context: Context): ImageContentExtractor =
        ocrEngine ?: synchronized(this) {
            ocrEngine ?: ImageContentExtractor(context.applicationContext).also { ocrEngine = it }
        }

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val result = mutableListOf<PagedChunk>()
        var chunkIdx = 0

        // Sprint E3 — observation-only doc/page profiler, found via the coroutine context
        // (null unless the capture toggle is on or a benchmark probe supplied one). Every
        // `prof?.…` below feeds an ALREADY-measured elapsed value; nothing is re-timed and
        // no control flow depends on it.
        val prof = kotlin.coroutines.coroutineContext[DocProfileRecorder]

        // Sprint P1.1 — sub-stage accumulators (nanos). Per-page stages are recorded
        // once per DOCUMENT (see IndexMetrics.Timing docs); flushed in the finally
        // below so a mid-loop failure still reports partial time, matching the
        // partial-on-failure semantics of the enclosing DOC_EXTRACT timer.
        var stripNs = 0L
        var nfcNs = 0L
        var trustNs = 0L
        var chunkNs = 0L

        val tOpen = System.nanoTime()
        val stream = openDocStream(context, uri)
        val openNs = System.nanoTime() - tOpen
        IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_OPEN, nsToMs(openNs))
        prof?.stageNs(ProfilerStage.PDF_OPEN, openNs)
        stream.use {
            val tLoad = System.nanoTime()
            val doc = PDDocument.load(stream)
            val loadNs = System.nanoTime() - tLoad
            IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_LOAD, nsToMs(loadNs))
            prof?.stageNs(ProfilerStage.PDF_LOAD, loadNs)
            doc.use {
                try {
                val pageCount = doc.numberOfPages
                prof?.pageCount = pageCount
                // Per-page final text, 1-indexed. Written by the strip/render producer
                // (trusted pages, render failures) and the OCR consumer (fallback pages)
                // at DISJOINT indices; read only after coroutineScope joins both, which
                // provides the happens-before edge.
                val pageTexts = arrayOfNulls<String>(pageCount + 1)

                // Sprint P3 — pipeline overlap. The producer owns PDDocument (PDFBox is
                // not thread-safe per document): it strips, scores and RENDERS on this
                // thread. Rendered pages are handed to FIFO OCR consumers that run the
                // unchanged ensemble — so the render/strip of page N+1 overlaps the OCR
                // of earlier pages.
                // Sprint P5 — the consumer pool is now `ocrConcurrency` wide (was 1), so a
                // scan-heavy document no longer OCRs strictly one page at a time. OCR order
                // is no longer page-order, which is output-safe: pageTexts is written at
                // DISJOINT page indices and chunking runs only AFTER coroutineScope joins
                // every consumer (the happens-before edge). Concurrency is memory-bounded by
                // OCR_FALLBACK_PERMIT; Tesseract stays serialized by its own runtime mutex.
                coroutineScope {
                    // Serializes ALL PDDocument access: the producer's stripping/rendering
                    // and a consumer's escalation-only 300dpi re-render. Uncontended on the
                    // hot path — a consumer takes it only when a page escalates.
                    val docMutex = Mutex()
                    // 1 = legacy strictly-serial path (exact rollback); higher runs that many
                    // OCR pages at once. Channel capacity matches so the producer can stay a
                    // few renders ahead of the pool.
                    val ocrConcurrency = AdaptivePdfOcrControl.pdfOcrConcurrency
                        .coerceIn(1, MAX_OCR_CONCURRENCY)
                    val jobs = Channel<OcrJob>(
                        capacity = ocrConcurrency,
                        onUndeliveredElement = { it.bitmap.recycle() },
                    )
                    repeat(ocrConcurrency) {
                        launch {
                            for (job in jobs) {
                                val t0 = System.currentTimeMillis()
                                prof?.beginPageOcr(job.page)
                                try {
                                    // OCR output is NFC-normalized at its own source (ImageContentExtractor).
                                    // Text-only: the QR payloads were never consumed on this path.
                                    // Sprint P4: escalation-ladder OCR — tier 1 (raw EN+HI) with
                                    // full-ensemble escalation; see extractPdfPageText.
                                    // Sprint P5: OcrPageTag carries this page number into the
                                    // page-agnostic ladder so its tier outcome attributes to the
                                    // right page even with the pool running several pages at once.
                                    pageTexts[job.page] = OCR_FALLBACK_PERMIT.withPermit {
                                        withContext(OcrPageTag(job.page)) {
                                            ocrEngine(context).extractPdfPageText(job.bitmap, job.renderHiRes)
                                        }
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    VaultLog.w(TAG, "Page ${job.page} OCR fallback failed: ${e.message} — keeping stripper text")
                                    pageTexts[job.page] = job.strippedFallback
                                } finally {
                                    job.bitmap.recycle()
                                    val ocrMs = System.currentTimeMillis() - t0
                                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_OCR_FALLBACK, ocrMs)
                                    prof?.pageOcrDone(job.page, ocrMs)
                                }
                            }
                        }
                    }
                    try {
                        var tStage = System.nanoTime()

                        suspend fun stripAllPages(): List<String> {
                            val stripper = PDFTextStripper()
                            var sentinel: String
                            var allRaw: String
                            val originalPageEnd = stripper.pageEnd
                            val stripStartedAt = System.nanoTime()
                            while (true) {
                                sentinel = "[[[PAGE_SEP_${java.util.UUID.randomUUID()}]]]"
                                stripper.pageEnd = "$originalPageEnd$sentinel"
                                allRaw = docMutex.withLock { stripper.getText(doc) }
                                if (allRaw.split(sentinel).size - 1 == pageCount) break
                            }
                            stripNs += System.nanoTime() - stripStartedAt
                            return allRaw.split(sentinel)
                        }

                        // A scanned PDF has no usable text layer. Stripping every page first is
                        // therefore a second full document traversal that produces only blanks.
                        // Probe first/middle/last with the same trust gate; any trusted page fails
                        // closed to the legacy full-strip path, preserving mixed/text PDFs.
                        val scanDominant = if (AdaptivePdfOcrControl.mode == AdaptivePdfOcrControl.Mode.SCAN_OPTIMIZED) {
                            val probeStripper = PDFTextStripper()
                            val probeStartedAt = System.nanoTime()
                            val probes = PdfExecutionPlanner.probePages(pageCount).map { probePage ->
                                probeStripper.startPage = probePage
                                probeStripper.endPage = probePage
                                val sampled = docMutex.withLock { probeStripper.getText(doc) }
                                TextTrustScorer.score(UnicodeText.nfc(sampled.trim()))
                            }
                            stripNs += System.nanoTime() - probeStartedAt
                            PdfExecutionPlanner.useOcrOnly(probes)
                        } else false
                        val rawPages = if (scanDominant) {
                            IndexMetrics.increment(IndexMetrics.Event.PDF_SCAN_DOMINANT_PLAN)
                            List(pageCount) { "" }
                        } else {
                            stripAllPages()
                        }

                        var renderer: PDFRenderer? = null // built once, only if some page needs OCR
                        for (page in 1..pageCount) {
                            val raw = rawPages.getOrElse(page - 1) { "" }
                            // NFC at the ingestion boundary — BEFORE scoring and chunking, so the
                            // gate judges (and Room/BM25 store) canonical bytes.
                            tStage = System.nanoTime()
                            val stripped = UnicodeText.nfc(raw.trim())
                            val pageNfcNs = System.nanoTime() - tStage
                            nfcNs += pageNfcNs
                            tStage = System.nanoTime()
                            val trust = TextTrustScorer.score(stripped)
                            val pageTrustNs = System.nanoTime() - tStage
                            trustNs += pageTrustNs
                            IndexMetrics.recordDuration(
                                IndexMetrics.Timing.PDF_TRUST_SCORE_MILLI, (trust.score * 1000).toLong()
                            )
                            prof?.pageScored(page, pageNfcNs, pageTrustNs, trust.score.toDouble(), trust.trusted)

                            if (trust.trusted) {
                                IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_TRUSTED)
                                pageTexts[page] = stripped
                            } else {
                                VaultLog.d(TAG, buildString {
                                    append("Page $page\nTrusted: false (score=${"%.2f".format(trust.score)})\nReasons:")
                                    trust.reasons.forEach { append("\n• $it") }
                                    append("\nOCR fallback executed.")
                                })
                                IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_OCR_FALLBACK)
                                try {
                                    val r = renderer ?: PDFRenderer(doc).also { renderer = it }
                                    val tRender = System.currentTimeMillis()
                                    val bitmap = docMutex.withLock {
                                        r.renderImageWithDPI(page - 1, OCR_RENDER_DPI, ImageType.RGB)
                                    }
                                    val renderMs = System.currentTimeMillis() - tRender
                                    IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_RENDER, renderMs)
                                    prof?.pageRendered(page, renderMs, bitmap.width, bitmap.height, bitmap.byteCount.toLong())
                                    // Exact check, not a heuristic: an all-white render has no ink to OCR.
                                    // Any scan noise, watermark, or faint mark fails closed and reaches OCR.
                                    if (RenderedPdfPageInspector.isExactlyBlank(bitmap)) {
                                        IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_BLANK_SKIPPED)
                                        bitmap.recycle()
                                        pageTexts[page] = ""
                                        continue
                                    }
                                    // Escalation-only 300dpi re-render for ensemble strategy 4
                                    // (Sprint P4 #2) — runs on the consumer, so it must take the
                                    // document mutex. Failure → null → the ensemble falls back
                                    // to its legacy 2× upscale of the 150dpi render.
                                    val renderHiRes: suspend () -> Bitmap? = {
                                        docMutex.withLock {
                                            val tHi = System.currentTimeMillis()
                                            try {
                                                r.renderImageWithDPI(page - 1, ESCALATION_RENDER_DPI, ImageType.RGB)
                                                    .also { hi ->
                                                        prof?.pageHiResRendered(page, hi.width, hi.height, hi.byteCount.toLong())
                                                    }
                                            } finally {
                                                val hiMs = System.currentTimeMillis() - tHi
                                                IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_RENDER_HIRES, hiMs)
                                                prof?.pageHiResMs(page, hiMs)
                                            }
                                        }
                                    }
                                    jobs.send(OcrJob(page, bitmap, stripped, renderHiRes))
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    // Render failed → keep stripper text (unchanged semantics).
                                    VaultLog.w(TAG, "Page $page OCR fallback failed: ${e.message} — keeping stripper text")
                                    pageTexts[page] = stripped
                                }
                            }
                        }
                    } finally {
                        jobs.close() // consumer drains what was sent, then completes
                    }
                }

                // Chunk in strict page order AFTER every page's text is final —
                // identical chunk inputs, ordering and chunkIdx numbering to the
                // pre-P3 serial loop (the chunker is pure).
                for (page in 1..pageCount) {
                    val pageText = pageTexts[page] ?: continue
                    if (pageText.isBlank()) continue
                    val tChunk = System.nanoTime()
                    chunker.chunk(pageText).forEach { chunk ->
                        result.add(PagedChunk(chunk, page, chunkIdx++))
                    }
                    chunkNs += System.nanoTime() - tChunk
                }
                } finally {
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_STRIP, nsToMs(stripNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_NFC, nsToMs(nfcNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_TRUST, nsToMs(trustNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_CHUNK, nsToMs(chunkNs))
                    // Sprint E3 — same accumulators, attributed to this document (observation only).
                    prof?.stageNs(ProfilerStage.PDF_STRIP, stripNs)
                    prof?.stageNs(ProfilerStage.PDF_NFC, nfcNs)
                    prof?.stageNs(ProfilerStage.PDF_TRUST, trustNs)
                    prof?.stageNs(ProfilerStage.PDF_CHUNK, chunkNs)
                }
            }
        }
        return result
    }

    /**
     * Sprint P6 — progressive per-page extraction. Where [extract] strips ALL pages up front,
     * then OCRs, then returns one big list (nothing searchable until the whole file is done),
     * this strips ONE page at a time and emits that page's chunks immediately — so a caller that
     * commits per batch makes page 1 searchable in ~ms and each later page the moment it finishes.
     *
     * Sprint P6.2 (Tier 1) — the strip/OCR overlap + concurrent-OCR pool + scan-dominant strip-skip
     * from [extract] are now ported here (device-measured: OCR was ~44% of pipeline time, the single
     * largest stage). Structure mirrors [extract]: a single producer owns the [PDDocument] (PDFBox is
     * not thread-safe per document) and strips/scores/renders page by page under [docMutex]; rendered
     * fallback pages are handed to a bounded pool of OCR consumers so the render/strip of later pages
     * overlaps the OCR of earlier ones. Concurrency = [AdaptivePdfOcrControl.pdfOcrConcurrency]
     * (1 = the exact pre-P6.2 strictly-serial path), clamped to [MAX_OCR_CONCURRENCY]; memory is
     * bounded by [OCR_FALLBACK_PERMIT] and Tesseract stays serialized by its own runtime mutex.
     *
     * Progressive-emission invariant preserved: pages are emitted in STRICT page order via
     * [emitReadyPages] (an ordered gate under [emitMutex]) — page 1 emits the instant it is final,
     * chunkIdx numbering matches the serial/batch path exactly, and a later page that finishes OCR
     * first is buffered until earlier pages emit (its searchability is not delayed — only its emit
     * ORDER is normalized). [onBatch] therefore stays sequential, honoring the interface contract.
     * [extract] is untouched and is the instant rollback path
     * ([AdaptivePdfOcrControl.progressivePdfIndexing] = false; or pdfOcrConcurrency = 1 to keep
     * streaming but force strictly-serial OCR).
     */
    override suspend fun extractStreaming(
        context: Context,
        uri: Uri,
        chunker: Chunker,
        onBatch: suspend (List<PagedChunk>) -> Unit,
    ) {
        // Sprint P6.1 — observation-only instrumentation, mirroring [extract]. Before this, the
        // streaming path recorded NONE of the IndexMetrics sub-stage timers and wired in no
        // profiler, so the whole per-import extraction cost landed in the benchmark's
        // "unattributed" bucket. Every `prof?.…` / recordDuration below feeds an already-measured
        // elapsed value; nothing is re-timed and NO control flow depends on it. The profiler is
        // found via the coroutine context (null unless the capture toggle is on).
        val prof = kotlin.coroutines.coroutineContext[DocProfileRecorder]

        // Per-document sub-stage accumulators (nanos). stripNs/nfcNs/trustNs are mutated only by the
        // single producer; chunkNs only inside [emitReadyPages] under [emitMutex]. All are read in
        // the finally AFTER coroutineScope joins (the happens-before edge). Flushed there so a
        // mid-loop failure still reports partial time — matches [extract]'s partial-on-failure.
        var stripNs = 0L
        var nfcNs = 0L
        var trustNs = 0L
        var chunkNs = 0L
        var chunkIdx = 0

        val tOpen = System.nanoTime()
        val stream = openDocStream(context, uri)
        val openNs = System.nanoTime() - tOpen
        IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_OPEN, nsToMs(openNs))
        prof?.stageNs(ProfilerStage.PDF_OPEN, openNs)
        stream.use {
            val tLoad = System.nanoTime()
            val doc = PDDocument.load(stream)
            val loadNs = System.nanoTime() - tLoad
            IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_LOAD, nsToMs(loadNs))
            prof?.stageNs(ProfilerStage.PDF_LOAD, loadNs)
            doc.use {
                try {
                val pageCount = doc.numberOfPages
                prof?.pageCount = pageCount

                // Final per-page text, 1-indexed. null = not yet ready. Written by the producer
                // (trusted / blank / render-fail pages) and by OCR consumers (fallback pages) at
                // DISJOINT indices; read only inside [emitReadyPages] under [emitMutex].
                val pageTexts = arrayOfNulls<String>(pageCount + 1)

                coroutineScope {
                    // Serializes ALL PDDocument access (PDFBox is not thread-safe per document): the
                    // producer's per-page strip/render and a consumer's escalation-only re-render.
                    val docMutex = Mutex()
                    // Serializes chunking + onBatch + chunkIdx so the streaming contract still holds
                    // (onBatch invoked sequentially, in page order) while OCR runs in parallel.
                    val emitMutex = Mutex()
                    val ocrConcurrency = AdaptivePdfOcrControl.pdfOcrConcurrency
                        .coerceIn(1, MAX_OCR_CONCURRENCY)
                    val jobs = Channel<OcrJob>(
                        capacity = ocrConcurrency,
                        onUndeliveredElement = { it.bitmap.recycle() },
                    )

                    // Ordered incremental emission: under [emitMutex], flush every contiguous ready
                    // page starting at the frontier. Called after each page's text is finalized (by
                    // producer and consumers); each call drains as far as the ready prefix allows.
                    // Because every write to pageTexts[p] is followed by an emitReadyPages() call,
                    // the globally-last such call (last mutex acquisition) sees all pages ready and
                    // drains to pageCount+1 — so no trailing emit is needed after the scope joins.
                    var nextToEmit = 1
                    suspend fun emitReadyPages() = emitMutex.withLock {
                        while (nextToEmit <= pageCount) {
                            val text = pageTexts[nextToEmit] ?: break
                            if (text.isNotBlank()) {
                                val tChunk = System.nanoTime()
                                val batch = chunker.chunk(text).map { PagedChunk(it, nextToEmit, chunkIdx++) }
                                chunkNs += System.nanoTime() - tChunk
                                if (batch.isNotEmpty()) onBatch(batch)
                            }
                            nextToEmit++
                        }
                    }

                    // OCR consumer pool — the unchanged ensemble (via extractPdfPageText); the permit
                    // + Tesseract's own mutex bound memory/native concurrency (mirrors [extract]).
                    repeat(ocrConcurrency) {
                        launch {
                            for (job in jobs) {
                                val t0 = System.currentTimeMillis()
                                prof?.beginPageOcr(job.page)
                                val text = try {
                                    // OcrPageTag carries this page number into the page-agnostic ladder
                                    // so its tier outcome attributes to the right page under concurrency.
                                    OCR_FALLBACK_PERMIT.withPermit {
                                        withContext(OcrPageTag(job.page)) {
                                            ocrEngine(context).extractPdfPageText(job.bitmap, job.renderHiRes)
                                        }
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    VaultLog.w(TAG, "Page ${job.page} OCR fallback failed: ${e.message} — keeping stripper text")
                                    job.strippedFallback
                                } finally {
                                    job.bitmap.recycle()
                                    val ocrMs = System.currentTimeMillis() - t0
                                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_OCR_FALLBACK, ocrMs)
                                    prof?.pageOcrDone(job.page, ocrMs)
                                }
                                pageTexts[job.page] = text
                                emitReadyPages()
                            }
                        }
                    }

                    try {
                        // A scanned PDF has no usable text layer, so per-page stripping is a wasted
                        // traversal that only yields blanks. Probe first/middle/last with the trust
                        // gate; if none is trusted, skip stripping and send every page straight to
                        // render+OCR. Any trusted probe fails closed to normal per-page stripping,
                        // preserving mixed/text PDFs (a faithful port of [extract]'s scan-dominant plan).
                        val scanDominant = if (AdaptivePdfOcrControl.mode == AdaptivePdfOcrControl.Mode.SCAN_OPTIMIZED) {
                            val probeStripper = PDFTextStripper()
                            val probeStartedAt = System.nanoTime()
                            val probes = PdfExecutionPlanner.probePages(pageCount).map { probePage ->
                                probeStripper.startPage = probePage
                                probeStripper.endPage = probePage
                                val sampled = docMutex.withLock { probeStripper.getText(doc) }
                                TextTrustScorer.score(UnicodeText.nfc(sampled.trim()))
                            }
                            stripNs += System.nanoTime() - probeStartedAt
                            PdfExecutionPlanner.useOcrOnly(probes)
                        } else false
                        if (scanDominant) IndexMetrics.increment(IndexMetrics.Event.PDF_SCAN_DOMINANT_PLAN)

                        val stripper = PDFTextStripper()
                        var renderer: PDFRenderer? = null // built once, only if some page needs OCR
                        for (page in 1..pageCount) {
                            // Strip THIS page only (scan-dominant skips stripping) so a text page 1 is
                            // ready in ms while later pages' strip/render overlaps earlier pages' OCR.
                            val raw = if (scanDominant) "" else {
                                stripper.startPage = page
                                stripper.endPage = page
                                val tStrip = System.nanoTime()
                                val text = docMutex.withLock { stripper.getText(doc) }
                                stripNs += System.nanoTime() - tStrip
                                text
                            }
                            val tNfc = System.nanoTime()
                            val stripped = UnicodeText.nfc(raw.trim())
                            val pageNfcNs = System.nanoTime() - tNfc
                            nfcNs += pageNfcNs
                            val tTrust = System.nanoTime()
                            val trust = TextTrustScorer.score(stripped)
                            val pageTrustNs = System.nanoTime() - tTrust
                            trustNs += pageTrustNs
                            IndexMetrics.recordDuration(
                                IndexMetrics.Timing.PDF_TRUST_SCORE_MILLI, (trust.score * 1000).toLong()
                            )
                            prof?.pageScored(page, pageNfcNs, pageTrustNs, trust.score.toDouble(), trust.trusted)

                            if (trust.trusted) {
                                IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_TRUSTED)
                                pageTexts[page] = stripped
                                emitReadyPages()
                            } else {
                                IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_OCR_FALLBACK)
                                try {
                                    val r = renderer ?: PDFRenderer(doc).also { renderer = it }
                                    val tRender = System.currentTimeMillis()
                                    val bitmap = docMutex.withLock {
                                        r.renderImageWithDPI(page - 1, OCR_RENDER_DPI, ImageType.RGB)
                                    }
                                    val renderMs = System.currentTimeMillis() - tRender
                                    IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_RENDER, renderMs)
                                    prof?.pageRendered(page, renderMs, bitmap.width, bitmap.height, bitmap.byteCount.toLong())
                                    if (RenderedPdfPageInspector.isExactlyBlank(bitmap)) {
                                        IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_BLANK_SKIPPED)
                                        bitmap.recycle()
                                        pageTexts[page] = ""
                                        emitReadyPages()
                                    } else {
                                        // Escalation-only 300dpi re-render for ensemble strategy 4;
                                        // runs on the consumer, so it takes docMutex. Failure → null →
                                        // the ensemble falls back to its legacy 2× upscale.
                                        val renderHiRes: suspend () -> Bitmap? = {
                                            docMutex.withLock {
                                                val tHi = System.currentTimeMillis()
                                                try {
                                                    runCatching {
                                                        r.renderImageWithDPI(page - 1, ESCALATION_RENDER_DPI, ImageType.RGB)
                                                            .also { hi ->
                                                                prof?.pageHiResRendered(page, hi.width, hi.height, hi.byteCount.toLong())
                                                            }
                                                    }.getOrNull()
                                                } finally {
                                                    val hiMs = System.currentTimeMillis() - tHi
                                                    IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_RENDER_HIRES, hiMs)
                                                    prof?.pageHiResMs(page, hiMs)
                                                }
                                            }
                                        }
                                        // Hand off to the pool; the consumer sets pageTexts[page] and
                                        // emits. Producer proceeds to strip/render the next page.
                                        jobs.send(OcrJob(page, bitmap, stripped, renderHiRes))
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    // Render failed → keep stripper text (unchanged semantics).
                                    VaultLog.w(TAG, "Page $page OCR fallback failed: ${e.message} — keeping stripper text")
                                    pageTexts[page] = stripped
                                    emitReadyPages()
                                }
                            }
                        }
                    } finally {
                        jobs.close() // consumers drain what was sent, then complete
                    }
                }
                // coroutineScope joined: every page's text is final and every ready page has been
                // emitted in strict order (nextToEmit reached pageCount+1) — see emitReadyPages.
                } finally {
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_STRIP, nsToMs(stripNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_NFC, nsToMs(nfcNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_TRUST, nsToMs(trustNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_CHUNK, nsToMs(chunkNs))
                    prof?.stageNs(ProfilerStage.PDF_STRIP, stripNs)
                    prof?.stageNs(ProfilerStage.PDF_NFC, nfcNs)
                    prof?.stageNs(ProfilerStage.PDF_TRUST, trustNs)
                    prof?.stageNs(ProfilerStage.PDF_CHUNK, chunkNs)
                }
            }
        }
    }
}

/** DOCX — whole-document text, then chunked. */
class WordFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    override val extensions = setOf("docx")
    override val tag = "word document"
    override val itemType = "word"

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val text = openDocStream(context, uri).use {
            org.apache.poi.xwpf.extractor.XWPFWordExtractor(
                org.apache.poi.xwpf.usermodel.XWPFDocument(it)
            ).text
        }
        return chunker.chunk(text).mapIndexed { idx, chunk -> PagedChunk(chunk, null, idx) }
    }
}

/** XLSX — flattened sheet text, then chunked. */
class ExcelFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    override val extensions = setOf("xlsx")
    override val tag = "spreadsheet excel"
    override val itemType = "excel"

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val fmt = org.apache.poi.ss.usermodel.DataFormatter()
        val text = openDocStream(context, uri).use {
            buildExcelText(org.apache.poi.xssf.usermodel.XSSFWorkbook(it), fmt)
        }
        return chunker.chunk(text).mapIndexed { idx, chunk -> PagedChunk(chunk, null, idx) }
    }

    private fun buildExcelText(
        wb: org.apache.poi.ss.usermodel.Workbook,
        fmt: org.apache.poi.ss.usermodel.DataFormatter,
    ) = buildString {
        wb.use { w -> for (s in w) { appendLine("═══ ${s.sheetName} ═══"); for (r in s) { val c = r.mapNotNull { fmt.formatCellValue(it).takeIf { v -> v.isNotBlank() } }; if (c.isNotEmpty()) appendLine(c.joinToString(" | ")) }; appendLine() } }
    }
}

/** EPUB — spine-ordered HTML text, then chunked. */
class EpubFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/epub+zip")
    override val extensions = setOf("epub")
    override val tag = "ebook epub"
    override val itemType = "epub"

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val text = openDocStream(context, uri).use { stream ->
            val zipBytes = stream.readBytes()
            val fileMap = mutableMapOf<String, String>()
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (n.endsWith(".html") || n.endsWith(".xhtml") || n.endsWith(".htm") || n.endsWith(".opf"))
                        fileMap[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
            val opf = fileMap.entries.firstOrNull { it.key.endsWith(".opf") }?.value
            val paths = parseSpineOrder(opf, fileMap.keys)
            buildString { paths.forEach { p -> fileMap[p]?.let { val c = stripHtml(it); if (c.isNotBlank()) appendLine(c) } } }
        }
        return chunker.chunk(text).mapIndexed { idx, chunk -> PagedChunk(chunk, null, idx) }
    }

    private fun parseSpineOrder(opfContent: String?, allPaths: Set<String>): List<String> {
        val htmlFilter = { p: String -> val l = p.lowercase(); l.endsWith(".html") || l.endsWith(".xhtml") || l.endsWith(".htm") }
        if (opfContent == null) return allPaths.filter(htmlFilter).sorted()
        val manifest = mutableMapOf<String, String>()
        Regex("""<item\s[^>]*id="([^"]+)"[^>]*href="([^"]+)"[^>]*/?>""").findAll(opfContent).forEach { manifest[it.groupValues[1]] = it.groupValues[2] }
        val spineIds = Regex("""<itemref\s[^>]*idref="([^"]+)"[^>]*/?>""").findAll(opfContent).map { it.groupValues[1] }.toList()
        if (spineIds.isEmpty() || manifest.isEmpty()) return allPaths.filter(htmlFilter).sorted()
        val opfDir = allPaths.firstOrNull { it.endsWith(".opf") }?.substringBeforeLast("/", "")?.let { if (it.isNotEmpty()) "$it/" else "" } ?: ""
        return spineIds.mapNotNull { id -> manifest[id]?.let { href -> allPaths.firstOrNull { it == opfDir + href || it.endsWith(href) } } }
    }

    private fun stripHtml(html: String) = html
        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), " ")
        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("&\\w+;"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Static dispatch table mime → extractor. No reflection; just a map built from a plain list.
 */
class DocumentFormatRegistry(extractors: List<FormatExtractor> = DEFAULT) {
    private val byMime: Map<String, FormatExtractor> =
        extractors.flatMap { e -> e.mimeTypes.map { it to e } }.toMap()

    private val mimeByExtension: Map<String, String> =
        extractors.flatMap { e -> e.extensions.map { it to e.mimeTypes.first() } }.toMap()

    fun extractorFor(mimeType: String): FormatExtractor? = byMime[mimeType]
    fun isSupported(mimeType: String): Boolean = byMime.containsKey(mimeType)
    val supportedMimeTypes: Set<String> get() = byMime.keys

    /**
     * The supported type to index a file as: the declared type when it is one, else the type
     * its file name says. Null when neither names a supported format.
     */
    fun resolveMimeType(declared: String?, fileName: String?): String? =
        declared?.trim()?.lowercase()?.takeIf(::isSupported)
            ?: mimeByExtension[fileName?.substringAfterLast('.', "")?.trim()?.lowercase()]

    companion object {
        /** Register a new format here — nothing else changes. */
        val DEFAULT: List<FormatExtractor> = listOf(
            PdfFormatExtractor(),
            WordFormatExtractor(),
            ExcelFormatExtractor(),
            EpubFormatExtractor(),
        )
    }
}
