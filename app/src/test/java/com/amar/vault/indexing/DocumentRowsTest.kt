package com.amar.vault.indexing

import com.amar.vault.ItemType
import com.amar.vault.VaultDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A document's pieces are tagged a page at a time. A piece is about two hundred words, so
 * what makes a page an invoice — its heading, its total — is rarely all in one piece.
 */
class DocumentRowsTest {

    private fun document(type: ItemType = ItemType.PDF) = VaultDocument(
        id = "doc", uri = "content://docs/doc", name = "Invoice-42.pdf", itemType = type,
        contentHash = "h", pageCount = null, chunkCount = 0, addedAt = 1L,
    )

    private val heading = PagedChunk("TAX INVOICE\nInvoice No: INV-2026-0042\nBill To: Amar", pdfPage = 1, chunkIndex = 0)
    private val items = PagedChunk("Widget 2 500.00 1,000.00\nGadget 1 180.00 180.00", pdfPage = 1, chunkIndex = 1)
    private val total = PagedChunk("Grand Total ₹1,180.00\nThank you for your business", pdfPage = 1, chunkIndex = 2)
    private val terms = PagedChunk("Goods once sold will not be taken back.", pdfPage = 2, chunkIndex = 3)

    @Test
    fun everyPieceOfAPageCarriesWhatThePageIs() {
        val rows = DocumentRows.of(document(), listOf(heading, items, total, terms), totalChunks = 4)
        assertEquals(
            listOf("pdf document invoice bill receipt", "pdf document invoice bill receipt", "pdf document invoice bill receipt", "pdf document"),
            rows.map { it.tags },
        )
    }

    @Test
    fun onePieceAtATimeThePageWouldNotBeRecognised() {
        // The control: tagged on its own, the piece that lists the items is no part of an
        // invoice — it says nothing of one.
        assertTrue(AutoTags.kinds(items.text).isEmpty())
        assertEquals("pdf document", AutoTags.of(items.text, ItemType.PDF))
    }

    @Test
    fun aPageIsNotTaggedForWhatAnotherPageIs() {
        val rows = DocumentRows.of(document(), listOf(heading, total, terms), totalChunks = 3)
        assertEquals("pdf document", rows.last().tags)
    }

    @Test
    fun aRowIsItsPieceAndNothingElse() {
        val row = DocumentRows.of(document(), listOf(heading, items), totalChunks = 0)[1]
        assertEquals("doc_chunk1", row.id)
        assertEquals("the text is the piece as it was read", items.text, row.ocrText)
        assertEquals(listOf("doc", 1, 1, 0), listOf(row.parentDocumentId, row.chunkIndex, row.pageNum, row.totalChunks))
        assertEquals(listOf("content://docs/doc", "Invoice-42.pdf", "h", ItemType.PDF), listOf(row.uri, row.sourceFile, row.contentHash, row.itemType))
    }

    @Test
    fun aDocumentWithoutPagesIsReadAStretchAtATime() {
        // A résumé in a Word file: the heading is in the first piece, the sections further on.
        val cv = listOf(
            PagedChunk("CURRICULUM VITAE\nAmar Singh, Jaipur", pdfPage = null, chunkIndex = 0),
            PagedChunk("Education: B.E. in Data Science, 2026", pdfPage = null, chunkIndex = 1),
            PagedChunk("Skills: Kotlin, SQL. Experience: one internship.", pdfPage = null, chunkIndex = 2),
        )
        val rows = DocumentRows.of(document(ItemType.WORD), cv, totalChunks = 3)
        assertTrue(rows.all { it.tags == "word document resume cv" })
        assertEquals("a piece of a document without pages keeps its place as its number", listOf(0, 1, 2), rows.map { it.pageNum })

        // A long one is not tagged as a whole for what one part of it is.
        val long = cv + (3 until 20).map { PagedChunk("Chapter text number $it", pdfPage = null, chunkIndex = it) }
        val longRows = DocumentRows.of(document(ItemType.WORD), long, totalChunks = 20)
        assertEquals(TagUnits.STRETCH, longRows.count { "resume" in it.tags })
    }

    @Test
    fun piecesAreGroupedInTheOrderGiven() {
        val pages = TagUnits.of(listOf(1, 1, 2, 2, 2, 5)) { it }
        assertEquals(listOf(listOf(1, 1), listOf(2, 2, 2), listOf(5)), pages)
        assertEquals(listOf(listOf("a", "b", "c", "d", "e", "f"), listOf("g")), TagUnits.of(listOf("a", "b", "c", "d", "e", "f", "g")) { null })
        assertTrue(TagUnits.of(emptyList<Int>()) { it }.isEmpty())
    }
}
