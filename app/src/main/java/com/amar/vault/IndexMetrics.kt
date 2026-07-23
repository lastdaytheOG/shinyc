package com.amar.vault

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight, in-memory operational metrics for the indexing subsystem.
 *
 * Why: correctness work is only trustworthy if it's observable. These counters/timers
 * make indexing duration, failures, retries, delete propagation, and reconciliation
 * visible for diagnostics — without a metrics framework and without affecting behaviour.
 *
 * Design: process-local, lock-free (AtomicLong / ConcurrentHashMap). Values reset on
 * process death (acceptable — this is diagnostics, not persisted analytics). No I/O on
 * the hot path; [snapshot] is the only aggregation and is called on demand.
 */
object IndexMetrics {

    // Named event counters (e.g. index.success, index.failure, delete.propagated).
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    // Named timers: total elapsed ms + sample count, for computing averages.
    private data class Timer(val totalMs: AtomicLong = AtomicLong(0), val count: AtomicLong = AtomicLong(0))
    private val timers = ConcurrentHashMap<String, Timer>()

    // ── Counters ────────────────────────────────────────────────────────────
    object Event {
        const val INDEX_SUCCESS = "index.success"
        const val INDEX_FAILURE = "index.failure"
        const val INDEX_SKIPPED_DUP = "index.skipped_duplicate"
        const val INDEX_RETRY = "index.retry"
        const val DOC_PARTIAL_REPAIR = "doc.partial_repair"
        const val DELETE_PROPAGATED = "delete.propagated"
        const val DELETE_FAILED = "delete.failed"
        const val RECONCILE_RUN = "reconcile.run"
        const val RECONCILE_ORPHANS_PRUNED = "reconcile.orphans_pruned"
        // Sprint 4A — PDF text-trust gate outcomes (per page).
        const val PDF_PAGE_TRUSTED = "pdf.page_trusted"
        const val PDF_PAGE_OCR_FALLBACK = "pdf.page_ocr_fallback"
        /** Rendered fallback page contained no non-white pixel, so OCR was provably unnecessary. */
        const val PDF_PAGE_BLANK_SKIPPED = "pdf.page_blank_skipped"
        /** Exact source bytes matched a complete prior PDF index; PDFBox/OCR/chunking were skipped. */
        const val PDF_SOURCE_REUSE_HIT = "pdf.source_reuse_hit"
        /** Three-page scan probe selected OCR-only extraction and skipped full text stripping. */
        const val PDF_SCAN_DOMINANT_PLAN = "pdf.scan_dominant_plan"
        // Sprint P4 — OCR escalation ladder outcomes (per fallback page). The
        // acceptance rate is THE validation signal for the ladder: accepted pages
        // ran 2 inference passes instead of the full ensemble's 7.
        const val PDF_OCR_TIER1_ACCEPTED = "pdf.ocr_tier1_accepted"
        const val PDF_OCR_ESCALATED = "pdf.ocr_escalated"
        // Sprint E4 — pathological-OCR guard hit counters (the "optimization hit rate"
        // signal: each counts one pipeline unit where a guard actually fired).
        const val OCR_TESS_TIMEOUT = "ocr.tess_timeout"
        const val OCR_TESS_INPUT_DOWNSCALED = "ocr.tess_input_downscaled"
        const val OCR_UPSCALE_BYPASSED = "ocr.upscale_bypassed"
    }

    object Timing {
        const val INDEX_TOTAL = "index.total_ms"
        const val OCR = "ocr_ms"
        const val EMBED = "embed_ms"
        const val META_EXTRACT = "meta_extract_ms"
        // Sprint 4A — PDF trust gate: OCR-fallback wall clock per page, and the trust
        // score recorded as milli-units (score × 1000) so the timer's avg is usable.
        const val PDF_OCR_FALLBACK = "pdf.ocr_fallback_ms"
        const val PDF_TRUST_SCORE_MILLI = "pdf.trust_score_milli"
        const val PDF_SOURCE_FINGERPRINT = "pdf.source_fingerprint_ms"
        // Sprint P1 — document-path stage timings (PDF/Word/Excel/EPUB), recorded by
        // DocumentIndexer.doIndex and surfaced by the Sprint 3C IndexingBenchmark.
        const val DOC_EXTRACT = "doc.extract_ms"
        const val DOC_DEDUP = "doc.dedup_ms"
        const val DOC_INDEX_TOTAL = "doc.index_total_ms"
        // Staged indexing: fast Room visibility and text-searchability before slower
        // metadata/vector enrichment. These are wall-clock checkpoints from index start.
        const val INDEX_FAST_COMMIT = "index.fast_commit_ms"
        const val INDEX_SEARCHABLE_COMMIT = "index.searchable_commit_ms"
        // Sprint P1.1 — PDF extraction sub-stages (all nested inside DOC_EXTRACT;
        // never sum them WITH it). One sample per document: the per-page stages are
        // sub-millisecond on trusted pages, so PdfFormatExtractor accumulates nanos
        // across the document and records a single rounded-ms sample per stage.
        // PDF_STRIP covers page traversal + PDFTextStripper together — PDFBox walks
        // the page tree inside getText(), so they are inseparable without changing
        // extraction behavior. Wall-clock trust time is PDF_TRUST (distinct from
        // PDF_TRUST_SCORE_MILLI, which records the score VALUE, not a duration).
        const val PDF_OPEN = "pdf.open_ms"
        const val PDF_LOAD = "pdf.load_ms"
        const val PDF_STRIP = "pdf.strip_ms"
        const val PDF_NFC = "pdf.nfc_ms"
        const val PDF_TRUST = "pdf.trust_ms"
        const val PDF_CHUNK = "pdf.chunk_ms"
        // Sprint P3 — OCR ensemble stage timings. Samples come from BOTH consumers of
        // the ensemble (screenshot/image indexing AND the PDF trust-gate fallback) —
        // one sample per engine invocation, not per page. There is no separate
        // Tesseract-Hindi metric because Tesseract runs ONCE per input as "eng+hin".
        // Since Sprint P3, PDF_OCR_FALLBACK covers the ensemble only; the page render
        // is measured separately as OCR_RENDER (the two overlap in wall time under
        // the pipelined fallback, so they must not be summed against wall clock).
        const val OCR_RENDER = "ocr.render_ms"
        // Sprint P4 — escalation-only 300dpi render (replaces the 2× raster upscale).
        const val OCR_RENDER_HIRES = "ocr.render_hires_ms"
        const val OCR_PREPROCESS = "ocr.preprocess_ms"
        const val OCR_MLKIT_EN = "ocr.mlkit_en_ms"
        const val OCR_MLKIT_HI = "ocr.mlkit_hi_ms"
        const val OCR_TESSERACT = "ocr.tesseract_ms"
        const val OCR_MERGE = "ocr.merge_ms"
        const val OCR_NFC = "ocr.nfc_ms"
        // Sprint E4 — time spent WAITING for the process-wide Tesseract mutex (previously
        // invisible: OCR_TESSERACT is timed inside the lock). This is the serialization-
        // amplification signal: one pathological page stalls every other document's
        // strategy 5 in the process, which is how a slow page becomes a slow BATCH.
        const val OCR_TESSERACT_QUEUE = "ocr.tesseract_queue_ms"
    }

    fun increment(name: String, delta: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
    }

    fun recordDuration(name: String, ms: Long) {
        val t = timers.computeIfAbsent(name) { Timer() }
        t.totalMs.addAndGet(ms)
        t.count.incrementAndGet()
    }

    /** Convenience: time a block, record under [name], and return its result. */
    inline fun <T> timed(name: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        try {
            return block()
        } finally {
            recordDuration(name, System.currentTimeMillis() - start)
        }
    }

    /** Immutable point-in-time view for diagnostics/logging. */
    fun snapshot(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        counters.forEach { (k, v) -> out[k] = v.get() }
        timers.forEach { (k, t) ->
            val c = t.count.get()
            out["$k.count"] = c
            out["$k.total"] = t.totalMs.get()
            out["$k.avg"] = if (c > 0) t.totalMs.get() / c else 0
        }
        return out
    }

    fun logSnapshot(tag: String = "IndexMetrics") {
        VaultLog.i(tag, "metrics: " + snapshot().entries.joinToString(", ") { "${it.key}=${it.value}" })
    }
}
