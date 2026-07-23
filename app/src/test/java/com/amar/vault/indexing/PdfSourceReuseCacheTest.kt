package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class PdfSourceReuseCacheTest {
    @Test
    fun `fingerprint is stable for equal bytes and changes for different bytes`() {
        val first = PdfSourceReuseCache.fingerprint(ByteArrayInputStream("same PDF bytes".toByteArray()))
        val second = PdfSourceReuseCache.fingerprint(ByteArrayInputStream("same PDF bytes".toByteArray()))
        val changed = PdfSourceReuseCache.fingerprint(ByteArrayInputStream("different PDF bytes".toByteArray()))
        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertEquals(64, first.length)
    }
}
