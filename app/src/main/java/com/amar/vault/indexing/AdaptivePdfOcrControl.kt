package com.amar.vault.indexing

/**
 * Process-wide emergency control for the PDF OCR executor.
 *
 * SCAN_OPTIMIZED is the default: it executes the measured tier-one gate and lets a three-page
 * probe bypass all-pages PDF text stripping only for scan-dominant documents.
 * LEGACY_ENSEMBLE keeps the same text-trust gate but makes every OCR-fallback page run the full
 * ensemble. It exists for immediate rollback if a validated quality run finds a regression.
 */
object AdaptivePdfOcrControl {
    enum class Mode { SCAN_OPTIMIZED, ADAPTIVE, LEGACY_ENSEMBLE }

    @Volatile
    var mode: Mode = Mode.SCAN_OPTIMIZED

    /**
     * Sprint P5 — per-document OCR fallback concurrency. 1 = the legacy strictly-serial consumer
     * (exact rollback); higher runs that many OCR pages at once, clamped to
     * [com.amar.vault.indexing.PdfFormatExtractor.MAX_OCR_CONCURRENCY]. Conservative default for
     * low-RAM devices (a student's phone) — each concurrent page holds a render bitmap + ML Kit
     * working set. Raise only after an on-device memory check.
     */
    @Volatile
    var pdfOcrConcurrency: Int = 2

    /**
     * Sprint P6 — progressive PDF indexing. When true, a PDF is stripped/OCR'd PAGE BY PAGE and
     * each page's chunks are committed to Room + BM25 as they are produced, so the document
     * becomes searchable within seconds (page 1 first) instead of only after the whole file
     * finishes. When false, the legacy all-or-nothing batch path runs (extract every page, then
     * one atomic commit). Set false to roll back instantly if the streaming path misbehaves on
     * device. PDF only — Word/Excel/EPUB are whole-document formats and always use the batch path.
     */
    @Volatile
    var progressivePdfIndexing: Boolean = true
}
