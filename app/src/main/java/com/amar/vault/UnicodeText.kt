package com.amar.vault

import java.text.Normalizer

/**
 * Sprint 4A — canonical Unicode normalization for text ingestion boundaries.
 *
 * NFC only (canonical composition): lossless, matches what ML Kit and Android
 * keyboards emit, and makes stored text byte-comparable with typed queries.
 * Deliberately NOT NFKC/NFKD — compatibility folding rewrites ligatures,
 * superscripts and fractions, altering user-visible stored text.
 *
 * Applied at exactly three boundaries (never mid-pipeline, never at Room insert):
 *  1. PDF stripper output, before trust scoring/chunking (PdfFormatExtractor);
 *  2. OCR merge output (ImageContentExtractor) — covers screenshots AND the
 *     PDF OCR fallback with one site;
 *  3. retrieval query entry (HybridSearchService).
 */
object UnicodeText {
    fun nfc(s: String): String =
        if (s.isEmpty() || Normalizer.isNormalized(s, Normalizer.Form.NFC)) s
        else Normalizer.normalize(s, Normalizer.Form.NFC)
}
