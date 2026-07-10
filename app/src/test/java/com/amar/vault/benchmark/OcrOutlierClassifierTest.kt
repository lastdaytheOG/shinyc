package com.amar.vault.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sprint E4 — pure-JVM tests for the measured-fields-only outlier classifier. */
class OcrOutlierClassifierTest {

    @Test
    fun `timeout has highest priority`() {
        assertEquals(
            OcrOutlierClassifier.CAT_TIMEOUT,
            OcrOutlierClassifier.classify(megapixels = 48.0, ocrMs = 95_000.0, trustScore = 0.05, mergedChars = 9000),
        )
    }

    @Test
    fun `oversized bitmap below the deadline classifies as large bitmap`() {
        assertEquals(
            OcrOutlierClassifier.CAT_LARGE_BITMAP,
            OcrOutlierClassifier.classify(megapixels = 12.0, ocrMs = 8_000.0, trustScore = null, mergedChars = null),
        )
    }

    @Test
    fun `normal-size page with huge text volume is dense text`() {
        assertEquals(
            OcrOutlierClassifier.CAT_DENSE_TEXT,
            OcrOutlierClassifier.classify(megapixels = 2.2, ocrMs = 6_000.0, trustScore = 0.4, mergedChars = 5_000),
        )
    }

    @Test
    fun `near-zero trust with nothing else measured is low contrast or noise`() {
        assertEquals(
            OcrOutlierClassifier.CAT_LOW_TRUST,
            OcrOutlierClassifier.classify(megapixels = 2.2, ocrMs = 4_000.0, trustScore = 0.05, mergedChars = null),
        )
    }

    @Test
    fun `unmeasured evidence fields never fire their categories`() {
        // No MP, no chars, no trust → only 'other' is honest.
        assertEquals(
            OcrOutlierClassifier.CAT_OTHER,
            OcrOutlierClassifier.classify(megapixels = null, ocrMs = 4_000.0, trustScore = null, mergedChars = null),
        )
    }

    @Test
    fun `healthy-looking slow page is other`() {
        assertEquals(
            OcrOutlierClassifier.CAT_OTHER,
            OcrOutlierClassifier.classify(megapixels = 2.2, ocrMs = 3_500.0, trustScore = 0.35, mergedChars = 800),
        )
    }
}
