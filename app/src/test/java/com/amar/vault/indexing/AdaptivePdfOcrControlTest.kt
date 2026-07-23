package com.amar.vault.indexing

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptivePdfOcrControlTest {
    @After
    fun reset() {
        AdaptivePdfOcrControl.mode = AdaptivePdfOcrControl.Mode.SCAN_OPTIMIZED
    }

    @Test
    fun `scan optimized executor is the default`() {
        assertEquals(AdaptivePdfOcrControl.Mode.SCAN_OPTIMIZED, AdaptivePdfOcrControl.mode)
    }

    @Test
    fun `legacy ensemble remains an explicit rollback`() {
        AdaptivePdfOcrControl.mode = AdaptivePdfOcrControl.Mode.LEGACY_ENSEMBLE
        assertEquals(AdaptivePdfOcrControl.Mode.LEGACY_ENSEMBLE, AdaptivePdfOcrControl.mode)
    }
}
