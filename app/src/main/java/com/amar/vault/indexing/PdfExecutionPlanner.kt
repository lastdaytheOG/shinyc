package com.amar.vault.indexing

/** Pure planner for PDF extraction work. */
internal object PdfExecutionPlanner {
    /** First, middle, last: enough to reject a mixed/text PDF without stripping every page. */
    fun probePages(pageCount: Int): List<Int> = when {
        pageCount <= 0 -> emptyList()
        pageCount == 1 -> listOf(1)
        pageCount == 2 -> listOf(1, 2)
        else -> listOf(1, (pageCount + 1) / 2, pageCount).distinct()
    }

    /**
     * OCR-only is allowed only when every sampled page fails the same trust gate that would have
     * routed that page to OCR in the legacy path. Any trusted probe preserves full stripping.
     */
    fun useOcrOnly(probeTrust: List<TextTrustScorer.TextTrustResult>): Boolean =
        probeTrust.isNotEmpty() && probeTrust.none { it.trusted }
}
