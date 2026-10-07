package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the C1 capture naming/indexing helpers.
 * No Android, no filesystem: these must run green on any host.
 *
 * The round-trip property ([fileNameFor] → [parseIndex]) is the one that matters most: index
 * adoption after a process restart depends on it, and if it breaks the next capture session
 * silently overwrites the corpus already collected.
 */
class OcrPageCaptureTest {

    // ── Filename shape ───────────────────────────────────────────────────────

    @Test
    fun `filenames are zero-padded so lexical order matches capture order`() {
        val first = OcrPageCapture.fileNameFor(1, "doc")
        val tenth = OcrPageCapture.fileNameFor(10, "doc")
        val hundredth = OcrPageCapture.fileNameFor(100, "doc")
        assertEquals("page_00001_doc.png", first)
        assertTrue(listOf(hundredth, first, tenth).sorted() == listOf(first, tenth, hundredth))
    }

    @Test
    fun `filenames always end in png because calibration input must be lossless`() {
        assertTrue(OcrPageCapture.fileNameFor(7, "anything").endsWith(".png"))
    }

    // ── Source id sanitization ───────────────────────────────────────────────

    @Test
    fun `path separators cannot escape the capture directory`() {
        val name = OcrPageCapture.fileNameFor(1, "../../etc/passwd")
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
        assertFalse(name.contains(".."))
    }

    @Test
    fun `characters illegal on other filesystems are replaced`() {
        // The corpus gets copied to a desktop workbench; a colon or a '@' in a filename is fine
        // on Linux but breaks on Windows, and these files must survive the trip.
        val sanitized = OcrPageCapture.sanitizeSourceId("pdfpage@1234:page?1")
        assertFalse(sanitized.contains("@"))
        assertFalse(sanitized.contains(":"))
        assertFalse(sanitized.contains("?"))
    }

    @Test
    fun `blank or null source id falls back to a stable label`() {
        assertEquals("unknown", OcrPageCapture.sanitizeSourceId(null))
        assertEquals("unknown", OcrPageCapture.sanitizeSourceId("   "))
        // A source id made entirely of separators must not sanitize down to an empty name,
        // which would produce "page_00001_.png" and collide across pages.
        assertEquals("unknown", OcrPageCapture.sanitizeSourceId("///"))
    }

    @Test
    fun `long source ids are truncated so the filename stays portable`() {
        val long = "a".repeat(500)
        assertTrue(OcrPageCapture.sanitizeSourceId(long).length <= 48)
        assertTrue(OcrPageCapture.fileNameFor(1, long).length < 255)
    }

    @Test
    fun `letters digits dot and dash survive sanitization`() {
        assertEquals("doc-1.2v3", OcrPageCapture.sanitizeSourceId("doc-1.2v3"))
    }

    // ── Index round-trip (restart safety) ────────────────────────────────────

    @Test
    fun `index survives the filename round trip`() {
        for (i in listOf(1, 9, 42, 500, 99999)) {
            assertEquals(i, OcrPageCapture.parseIndex(OcrPageCapture.fileNameFor(i, "src")))
        }
    }

    @Test
    fun `round trip holds when the source id itself starts with digits`() {
        // Guards the parser against consuming digits past the index field.
        val name = OcrPageCapture.fileNameFor(7, "2024doc")
        assertEquals(7, OcrPageCapture.parseIndex(name))
    }

    @Test
    fun `non-capture filenames parse as null and are ignored during adoption`() {
        assertNull(OcrPageCapture.parseIndex("capture_index.tsv"))
        assertNull(OcrPageCapture.parseIndex("README.md"))
        assertNull(OcrPageCapture.parseIndex("page_notanumber.png"))
        assertNull(OcrPageCapture.parseIndex("something_00001.png"))
    }

    // ── Cap accounting ───────────────────────────────────────────────────────

    @Test
    fun `stats report at-cap only once the page limit is actually reached`() {
        fun stats(captured: Int, max: Int) = OcrPageCapture.Stats(
            enabled = true, captured = captured, skippedAtCap = 0,
            failed = 0, bytes = 0, maxPages = max,
        )
        assertFalse(stats(499, 500).atCap)
        assertTrue(stats(500, 500).atCap)
        assertTrue(stats(501, 500).atCap)
    }

    @Test
    fun `megabytes conversion is binary not decimal`() {
        val s = OcrPageCapture.Stats(
            enabled = true, captured = 1, skippedAtCap = 0,
            failed = 0, bytes = 10L * 1024 * 1024, maxPages = 500,
        )
        assertEquals(10.0, s.megabytes, 1e-9)
    }
}
