package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a document looks like once search has found it: the card must say which file it is, and
 * a file must be one card. Both were wrong in ways that made a found PDF look missing.
 */
class DocumentSearchResultTest {

    private fun page(doc: String, index: Int, text: String, itemType: ItemType = ItemType.PDF, file: String = "$doc.pdf") =
        VaultItem(
            id = "${doc}_chunk$index", uri = "content://docs/$doc", ocrText = text, lang = "en",
            itemType = itemType, pageNum = index + 1, sourceFile = file, timestamp = 0L,
            parentDocumentId = doc, chunkIndex = index,
        )

    private fun VaultItem.asRow(stashId: String = "search_$id", category: String = "") = StashItemWithVaultItem(
        stashId = stashId, sessionId = null, vaultItemId = id, vaultType = "SAVED", category = category,
        savedAt = timestamp, sourceApp = sourceApp.orEmpty(), isFavorite = false, createdAt = timestamp,
        userNote = null, thumbnailPath = null, uri = uri, ocrText = ocrText, itemType = itemType,
        sourceFile = sourceFile, timestamp = timestamp, title = title, mimeType = mimeType,
        tags = tags, parentDocumentId = parentDocumentId,
    )

    // ── The card: a document is a document, whatever its pages say ──────────────────────

    @Test
    fun aPdfPageThatPrintsAPriceIsStillThePdf() {
        for (text in listOf("fine of ₹ 50000 shall be paid", "priced at \$20 per copy", "Rs. 14 per litre")) {
            assertEquals(text, ContentSpecies.PDF, ContentSpecies.classify(page("act", 3, text)))
            assertEquals(text, ContentSpecies.PDF, ContentSpecies.classify(page("act", 3, text).asRow()))
        }
    }

    @Test
    fun aPdfPageThatMentionsAPlaceIsStillThePdf() {
        for (text in listOf("the location of the depot", "the distance between the two chains")) {
            assertEquals(text, ContentSpecies.PDF, ContentSpecies.classify(page("paper", 0, text)))
        }
    }

    @Test
    fun aSharedPdfIsAPdfBeforeAndAfterItsTextIsRead() {
        val saved = VaultItem(
            id = "saved", uri = "/data/user/0/com.amar.vault/files/shared_imports/abc.pdf", ocrText = "",
            lang = "en", itemType = ItemType.PDF, sourceFile = "Bill.pdf", timestamp = 0L,
            title = "Bill.pdf", mimeType = "application/pdf",
        )
        assertEquals(ContentSpecies.PDF, ContentSpecies.classify(saved))
        assertEquals(ContentSpecies.PDF, ContentSpecies.classify(saved.copy(ocrText = "total ₹ 1,200")))
    }

    @Test
    fun wordExcelAndEpubPagesAreNeverProductsOrPlaces() {
        val text = "unit price ₹ 400, distance to location 12 km"
        assertEquals(ContentSpecies.DOCUMENT, ContentSpecies.classify(page("notes", 0, text, ItemType.WORD, "notes.docx")))
        assertEquals(ContentSpecies.DOCUMENT, ContentSpecies.classify(page("sheet", 0, text, ItemType.EXCEL, "sheet.xlsx")))
        assertEquals(ContentSpecies.DOCUMENT, ContentSpecies.classify(page("book", 0, text, ItemType.EPUB, "book.epub")))
    }

    @Test
    fun aScreenshotIsStillReadByItsText() {
        val shot = VaultItem(
            id = "s", uri = "content://media/1", ocrText = "Paid ₹ 250 to Ravi", lang = "en",
            itemType = ItemType.SCREENSHOT, timestamp = 0L,
        )
        assertEquals("unchanged for images", ContentSpecies.PRODUCT, ContentSpecies.classify(shot))
    }

    // ── The list: one card per document, and the saved item when there is one ───────────

    @Test
    fun aPageOfASavedDocumentIsShownAsThatSavedDocument() {
        val saved = VaultItem(
            id = "bill", uri = "/files/shared_imports/x.pdf", ocrText = "", lang = "en",
            itemType = ItemType.PDF, sourceFile = "Bill.pdf", timestamp = 0L, title = "Bill.pdf",
        )
        val savedRow = saved.asRow(stashId = "stash-1", category = "Bills")
        val hit = page("bill", 4, "amount due in March")

        val cards = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), mapOf("bill" to savedRow))

        assertEquals(listOf("stash-1"), cards.map { it.row.stashId })
        assertEquals("the saved item keeps its folder", "Bills", cards.single().row.category)
    }

    @Test
    fun aDocumentThatWasNeverSavedKeepsItsOwnPage() {
        val hit = page("manual", 2, "reset the device")
        val cards = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap())
        assertEquals(listOf("search_manual_chunk2"), cards.map { it.row.stashId })
    }

    @Test
    fun manyPagesOfOneDocumentAreOneCard() {
        val saved = VaultItem(
            id = "bill", uri = "/files/shared_imports/x.pdf", ocrText = "", lang = "en",
            itemType = ItemType.PDF, sourceFile = "Bill.pdf", timestamp = 0L, title = "Bill.pdf",
        )
        val savedRow = saved.asRow(stashId = "stash-1")
        val billPages = (0 until 5).map { page("bill", it, "page $it") }
        val manualPages = (0 until 3).map { page("manual", it, "page $it") }
        // The order a browse by type gives: every row of everything, the saved item among them.
        val matched = billPages + saved + manualPages
        val rows = billPages.map { it.asRow() } + savedRow + manualPages.map { it.asRow() }

        val cards = oneCardPerDocument(rows, matched, mapOf("bill" to savedRow))

        assertEquals(listOf("stash-1", "search_manual_chunk0"), cards.map { it.row.stashId })
    }

    // ── Where in the document: the page a tap opens, and the words shown for it ─────────

    private val longPage = "The Authority shall maintain a register. " +
        "Every depository shall furnish to the Authority such returns as may be prescribed, " +
        "and a participant shall keep its records open to inspection at all reasonable hours of the day."

    @Test
    fun aHitCarriesThePageAndTheWordsThatMatched() {
        val hit = page("act", 36, longPage)
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("depository")).single()
        assertEquals("pages are counted from 1", 37, card.page)
        assertTrue(card.excerpt, card.excerpt!!.contains("depository"))
    }

    @Test
    fun theSavedDocumentStillOpensOnThePageThatMatched() {
        val saved = VaultItem(
            id = "act", uri = "/files/shared_imports/x.pdf", ocrText = "", lang = "en",
            itemType = ItemType.PDF, sourceFile = "Act.pdf", timestamp = 0L, title = "Act.pdf",
        )
        val hit = page("act", 36, longPage)
        val card = oneCardPerDocument(
            listOf(hit.asRow()), listOf(hit), mapOf("act" to saved.asRow(stashId = "stash-9")), listOf("depository"),
        ).single()
        assertEquals("stash-9", card.row.stashId)
        assertEquals(37, card.page)
    }

    @Test
    fun aDocumentFoundByItsNameAloneHasNoPageToOpenOn() {
        val hit = page("roadmap", 71, "annexure with tables of state wise capacity")
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("roadmap")).single()
        assertNull(card.page)
        assertNull(card.excerpt)
    }

    @Test
    fun aWordFileHasWordsToShowButNoPage() {
        val hit = page("notes", 3, longPage, itemType = ItemType.WORD, file = "notes.docx")
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("depository")).single()
        assertNull("a chunk of a Word file is not a page", card.page)
        assertTrue(card.excerpt!!.contains("depository"))
    }

    @Test
    fun theExcerptIsCutBetweenWordsAndSaysWhatWasLeftOut() {
        val excerpt = MatchExcerpt.of(longPage, listOf("depository"))!!
        assertTrue(excerpt, excerpt.startsWith("…") && excerpt.endsWith("…"))
        val inner = excerpt.removePrefix("…").removeSuffix("…")
        assertTrue("whole words only: $inner", longPage.contains(" $inner ") )
    }

    @Test
    fun aShortPageIsShownWhole() {
        val page = listOf("Paid to  the", "depository today").joinToString(separator = System.lineSeparator())
        assertEquals("Paid to the depository today", MatchExcerpt.of(page, listOf("depository")))
    }

    @Test
    fun theExcerptIsAboutTheLongestWordFound() {
        val text = "Report of the committee. " + "filler ".repeat(40) + "The ministry published the law."
        val excerpt = MatchExcerpt.of(text, listOf("ministry", "of", "law"))!!
        assertTrue(excerpt, excerpt.contains("ministry"))
        assertFalse("not the first 'of' on the page", excerpt.contains("Report"))
    }

    @Test
    fun aPagesTagsAreNotItsText() {
        val row = tagged(page("bill", 0, "total amount due"), "pdf document invoice billing")
        assertNull(MatchExcerpt.of(row.ocrText, listOf("invoice")))
    }

    // ── Pictures, and results that are only spelt nearly the same ───────────────────────

    @Test
    fun aPictureShowsTheWordsReadOffItThatMatched() {
        val shot = VaultItem(
            id = "shot", uri = "content://media/1", ocrText = "18:09 ELLA CIAO. Add to queue", lang = "en",
            itemType = ItemType.SCREENSHOT, timestamp = 0L,
        )
        val card = oneCardPerDocument(listOf(shot.asRow()), listOf(shot), emptyMap(), listOf("queue")).single()
        assertTrue(card.excerpt, card.excerpt!!.contains("queue"))
        assertNull("a picture has no page", card.page)
        assertNull(card.similarWord)
    }

    @Test
    fun aLookAlikeHitSaysWhichWordItIsHereFor() {
        val hit = page("contract", 12, "the parties agree that each clause of this agreement stands alone")
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("claude")).single()
        assertEquals("clause", card.similarWord)
        assertTrue(card.excerpt, card.excerpt!!.contains("clause"))
        assertEquals("and it opens where that word is", 13, card.page)
    }

    @Test
    fun aDocumentFoundByItsNameIsNotALookAlikeHit() {
        // "roadmap" is in the file's name; a word on the page that resembles it is beside the point.
        val hit = page("plan", 2, "the roadway is closed for repairs", file = "Roadmap 2026.pdf")
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("roadmap")).single()
        assertNull(card.similarWord)
        assertNull(card.page)
    }

    // ── Found by a tag the indexer added, not by anything it says ───────────────────────

    /** [row] as the indexer stores it: the page, and the tags it gave the page. */
    private fun tagged(row: VaultItem, tags: String) = row.copy(tags = tags)

    @Test
    fun aRowFoundByATagSaysWhichTag() {
        val hit = tagged(page("act", 3, "the assessee shall pay the amount due"), "pdf document invoice billing receipt")
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("invoice")).single()
        assertEquals("invoice", card.filedUnder)
        assertNull("a tag is not on a page", card.excerpt)
        assertNull(card.page)
    }

    @Test
    fun aTagIsNotTheReasonWhenThePageSaysTheWord() {
        val hit = tagged(page("bill", 0, "this is not a gst invoice"), "pdf document invoice billing receipt")
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("invoice")).single()
        assertNull(card.filedUnder)
        assertTrue(card.excerpt!!.contains("invoice"))
        assertEquals(1, card.page)
    }

    @Test
    fun aMisspeltWordFindsTheTagItWasMeantToBe() {
        val hit = tagged(page("act", 3, "the assessee shall pay the amount due"), "pdf document invoice billing receipt")
        val card = oneCardPerDocument(listOf(hit.asRow()), listOf(hit), emptyMap(), listOf("recipt")).single()
        assertEquals("receipt", card.filedUnder)
        assertNull(card.similarWord)
    }

    @Test
    fun aTableInThePageIsPartOfThePage() {
        // A page can have a bracketed block of its own. (How such a page was told from its
        // tags while the two were stored as one text is FormerStoredTextTest's subject.)
        val row = tagged(
            page("act", 3, "Schedule" + 10.toChar() + "[Table: Sl. No. | Rate of invoice]" + 10.toChar() + "as amended"),
            "pdf document tax government",
        )
        val card = oneCardPerDocument(listOf(row.asRow()), listOf(row), emptyMap(), listOf("invoice")).single()
        assertTrue("the table is part of the page", card.excerpt!!.contains("invoice"))
        assertNull(card.filedUnder)
    }

    @Test
    fun hindiIsFoundToo() {
        val excerpt = MatchExcerpt.of("भारत सरकार कौशल विकास और उद्यमिता मंत्रालय प्रशिक्षण महानिदेशालय", listOf("प्रशिक्षण"))
        assertTrue(excerpt!!.contains("प्रशिक्षण"))
    }
}
