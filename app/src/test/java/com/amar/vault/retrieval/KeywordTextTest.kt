package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import com.amar.vault.VaultItemSearchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the keyword engine is given for a row, whenever it is given it. */
class KeywordTextTest {

    private val page = VaultItem(
        id = "act_chunk3", uri = "content://docs/act", ocrText = "the assessee shall pay the amount due", lang = "en",
        itemType = ItemType.PDF, pageNum = 4, sourceFile = "Finance_Act-2026.pdf", timestamp = 0L,
        tags = "pdf document financial payment monetary", parentDocumentId = "act", chunkIndex = 3,
    )

    @Test
    fun aRowIsIndexedByItsTextItsTagsItsTypeAndItsName() {
        assertEquals(
            "the assessee shall pay the amount due pdf document financial payment monetary pdf " +
                "Finance_Act-2026.pdf Finance Act 2026 pdf",
            KeywordText.of(page),
        )
    }

    @Test
    fun theTextIsTheSameWhenTheRowIsIndexedAndWhenTheEngineIsFilledAtStartUp() {
        // Start-up reads a narrower row from the database; it must build what indexing built.
        val atStartUp = VaultItemSearchData(1L, page.id, page.ocrText, page.tags, page.itemType, page.sourceFile, page.title)
        assertEquals(KeywordText.of(page), KeywordText.of(atStartUp))
    }

    @Test
    fun theTextIsTheSameWhenADocumentsNameIsWorkedOutOnceForAllItsPages() {
        val shot = VaultItem(id = "s", uri = "content://media/1", ocrText = "paid rs 250", lang = "en",
            itemType = ItemType.SCREENSHOT, sourceFile = "Finance_Act-2026.pdf", timestamp = 0L)
        val rows = listOf(
            page, page.copy(id = "act_chunk4", ocrText = "another page"),
            page.copy(id = "other_chunk0", sourceFile = "Other (2).pdf"),
            page.copy(id = "titled", title = "Its own title"),
            shot,   // a picture that happens to carry a document's file name is not named by it
        )
        // At start-up the engine is given a row's text and the rest side by side.
        val forMany = KeywordText.ForManyRows()
        for (row in rows) {
            val atStartUp = VaultItemSearchData(1L, row.id, row.ocrText, row.tags, row.itemType, row.sourceFile, row.title)
            assertEquals(row.id, KeywordText.of(row), "${atStartUp.ocrText} ${forMany.besidesTheText(atStartUp)}")
        }
    }

    @Test
    fun aPictureHasNoFileNameToBeFoundBy() {
        val shot = VaultItem(id = "s", uri = "content://media/1", ocrText = "paid rs 250", lang = "en",
            itemType = ItemType.SCREENSHOT, sourceFile = "2336", timestamp = 0L, tags = "receipt payment bill invoice")
        val text = KeywordText.of(shot)
        assertTrue(text, text.startsWith("paid rs 250 receipt payment bill invoice screenshot"))
        assertTrue("the media number is not a name", "2336" !in text)
    }
}
