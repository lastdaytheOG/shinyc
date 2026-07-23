package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExecutionPlannerTest {
    @Test
    fun `probe pages cover the document endpoints and middle`() {
        assertEquals(listOf(1), PdfExecutionPlanner.probePages(1))
        assertEquals(listOf(1, 2), PdfExecutionPlanner.probePages(2))
        assertEquals(listOf(1, 5, 9), PdfExecutionPlanner.probePages(9))
    }

    @Test
    fun `one trusted sample preserves the full text extraction path`() {
        val blank = TextTrustScorer.score("")
        val text = TextTrustScorer.score("This is a healthy digital text page with enough normal words to trust safely.")
        assertFalse(PdfExecutionPlanner.useOcrOnly(listOf(blank, text, blank)))
        assertTrue(PdfExecutionPlanner.useOcrOnly(listOf(blank, blank)))
    }
}
