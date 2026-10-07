package com.amar.vault.indexing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which files the document indexer takes. The type a provider declares is not always usable —
 * a PDF often arrives as "application/octet-stream", or with no type at all — and such a file
 * used to be skipped without a word. Its name still says what it is.
 */
class DocumentFormatRegistryTest {

    private val registry = DocumentFormatRegistry()
    private val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    @Test
    fun aDeclaredSupportedTypeIsUsedAsIs() {
        assertEquals("application/pdf", registry.resolveMimeType("application/pdf", "scan0042"))
        assertEquals("application/pdf", registry.resolveMimeType("Application/PDF", null))
    }

    @Test
    fun anUnusableTypeFallsBackToTheFileName() {
        assertEquals("application/pdf", registry.resolveMimeType("application/octet-stream", "Marksheet 2023.PDF"))
        assertEquals("application/pdf", registry.resolveMimeType("", "bill.pdf"))
        assertEquals("application/pdf", registry.resolveMimeType(null, "bill.pdf"))
        assertEquals(docx, registry.resolveMimeType("*/*", "notes.docx"))
        assertEquals("application/epub+zip", registry.resolveMimeType("application/zip", "book.epub"))
    }

    @Test
    fun whatIsNotADocumentIsNotOne() {
        assertNull(registry.resolveMimeType("image/jpeg", "photo.jpg"))
        assertNull(registry.resolveMimeType("vnd.android.document/directory", "Tax papers"))
        assertNull(registry.resolveMimeType("application/octet-stream", "archive"))
        assertNull(registry.resolveMimeType(null, null))
    }

    @Test
    fun everyFormatCanBeFoundByItsExtension() {
        for (extractor in DocumentFormatRegistry.DEFAULT) {
            for (extension in extractor.extensions) {
                assertEquals(extractor.mimeTypes.first(), registry.resolveMimeType(null, "file.$extension"))
            }
        }
    }
}
