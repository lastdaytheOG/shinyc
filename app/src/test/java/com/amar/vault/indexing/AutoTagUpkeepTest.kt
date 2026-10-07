package com.amar.vault.indexing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.ItemType
import com.amar.vault.QrPayloads
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What is already stored is tagged again when the rules change: from its stored text, once,
 * leaving everything but the tags as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AutoTagUpkeepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: VaultDatabase
    private lateinit var upkeep: AutoTagUpkeep

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        upkeep = AutoTagUpkeep(db, context)
    }

    @After
    fun close() = db.close()

    private fun picture(id: String, text: String, tags: String, type: ItemType = ItemType.SCREENSHOT, qr: String? = null) =
        VaultItem(id = id, uri = "content://media/$id", ocrText = text, lang = "en", itemType = type, timestamp = 5L, tags = tags, qrPayload = qr)

    private fun piece(doc: String, index: Int, page: Int, text: String, tags: String, type: ItemType = ItemType.PDF) = VaultItem(
        id = "${doc}_chunk$index", uri = "content://docs/$doc", ocrText = text, lang = "en", itemType = type, pageNum = page,
        sourceFile = "$doc.pdf", timestamp = 9L, tags = tags, contentHash = "h-$doc", parentDocumentId = doc, chunkIndex = index,
    )

    private suspend fun store(vararg rows: VaultItem) {
        db.vaultDao().insertAll(rows.toList())
        rows.mapNotNull { it.parentDocumentId }.distinct().forEach { id ->
            val first = rows.first { it.parentDocumentId == id }
            db.vaultDocumentDao().upsert(VaultDocument(id, first.uri, first.sourceFile, first.itemType, first.contentHash, null,
                rows.count { it.parentDocumentId == id }, 1L))
        }
    }

    private suspend fun tagsOf(id: String) = db.vaultDao().getByIds(listOf(id)).single().tags

    @Test
    fun whatTheOldRulesGotWrongIsPutRight() = runBlocking {
        store(
            // As the rules before tagged them.
            picture("chat", "Top users this hour\nOthers are typing", "receipt payment bill invoice"),
            picture("gpay", "₹250\nPaid to Ravi Kumar\nUPI transaction ID\n627912345678", "receipt payment bill invoice"),
            piece("act", 0, 1, "For example, the company shall examine its remarks.", "pdf document academic education tax government"),
            piece("act", 1, 1, "No tax is due on this page.", "pdf document tax government"),
        )

        val told = mutableListOf<String>()
        val did = assertNotNullAnd(upkeep.retagIfRulesChanged { told += it })

        assertEquals("whoever indexes tags is told each row that changed, once",
            listOf("act_chunk0", "act_chunk1", "chat", "gpay"), told.sorted())
        assertEquals("users is not rupees", "", tagsOf("chat"))
        assertEquals("payment receipt transaction", tagsOf("gpay"))
        assertEquals("pdf document", tagsOf("act_chunk0"))
        assertEquals("pdf document", tagsOf("act_chunk1"))
        assertEquals(4, did.itemsChecked)
        assertEquals(4, did.itemsChanged)
        assertEquals(mapOf("payment" to 1), did.recognised)
    }

    @Test
    fun aDocumentIsTaggedAPageAtATime() = runBlocking {
        store(
            piece("inv", 0, 1, "TAX INVOICE\nInvoice No: INV-42", "pdf document"),
            piece("inv", 1, 1, "Widget 2 500.00 1,000.00", "pdf document"),
            piece("inv", 2, 2, "Goods once sold will not be taken back.", "pdf document"),
        )
        upkeep.retagAll()
        assertEquals("pdf document invoice bill receipt", tagsOf("inv_chunk0"))
        assertEquals("the piece that only lists the items is part of the invoice's page", "pdf document invoice bill receipt", tagsOf("inv_chunk1"))
        assertEquals("pdf document", tagsOf("inv_chunk2"))
    }

    @Test
    fun onlyTheTagsChange() = runBlocking {
        val before = piece("act", 0, 3, "For example, the company shall examine its remarks.", "pdf document academic education")
        store(before)
        upkeep.retagAll()
        assertEquals(before.copy(tags = "pdf document"), db.vaultDao().getByIds(listOf(before.id)).single())
        // And the row is still found by the words on its page.
        assertEquals(listOf(before.id), db.vaultDao().searchFts("company").first().map { it.id })
    }

    @Test
    fun itIsDoneOncePerVersionOfTheRules() = runBlocking {
        store(picture("chat", "Top users this hour", "receipt payment bill invoice"))
        assertNotNull(upkeep.retagIfRulesChanged())
        assertNull("the stored tags are current: nothing to do", upkeep.retagIfRulesChanged())

        // A row that arrives wrongly tagged afterwards is left for the next version of the rules…
        store(picture("later", "Top users this hour", "receipt"))
        assertNull(AutoTagUpkeep(db, context).retagIfRulesChanged())
        assertEquals("receipt", tagsOf("later"))
        // …and doing it all again changes only what is wrong.
        assertEquals("asking first writes nothing", 1, upkeep.countOutOfDate())
        assertEquals("receipt", tagsOf("later"))
        assertEquals(1, upkeep.retagAll().itemsChanged)
        assertEquals(0, upkeep.countOutOfDate())
        assertEquals(0, upkeep.retagAll().itemsChanged)
    }

    @Test
    fun aPictureWithACodeLosesTheWordsThatWereAddedToItsText() = runBlocking {
        val upi = "upi://pay?pa=ravi@okaxis&pn=Ravi Kumar"
        store(
            picture("qr", "Scan to pay\nRavi Kumar ravi@okaxis upi payment qr scanner", "receipt payment bill invoice", ItemType.PHOTO, QrPayloads.join(listOf(upi))),
            picture("menu", "https://example.org/menu qr scanner barcode", "", ItemType.PHOTO, QrPayloads.join(listOf("https://example.org/menu"))),
            // No code was read off this one: its text is its own, whatever it says.
            picture("poster", "Free qr scanner barcode app", "", ItemType.PHOTO),
        )
        upkeep.retagAll()

        val rows = db.vaultDao().getAll().associateBy { it.id }
        assertEquals("Scan to pay\nRavi Kumar ravi@okaxis", rows.getValue("qr").ocrText)
        assertEquals("qr code upi payment", rows.getValue("qr").tags)
        assertEquals("https://example.org/menu", rows.getValue("menu").ocrText)
        assertEquals("qr code", rows.getValue("menu").tags)
        assertEquals("Free qr scanner barcode app", rows.getValue("poster").ocrText)
        // The words are found as tags now, and no longer as something the picture says.
        assertEquals(emptyList<String>(), db.vaultDao().searchFts("scanner").first().map { it.id }.filter { it != "poster" })
    }

    @Test
    fun savedLinksAndFilesAreLeftAsTheyAre() = runBlocking {
        val link = VaultItem(id = "link", uri = "https://example.org", ocrText = "Your OTP is 4471", lang = "en", itemType = ItemType.LINK, timestamp = 1L)
        val savedPdf = VaultItem(id = "bill", uri = "/files/x.pdf", ocrText = "", lang = "en", itemType = ItemType.PDF, timestamp = 1L, sharedAt = 1L)
        store(link, savedPdf)
        assertEquals(0, upkeep.retagAll().itemsChanged)
        assertEquals(listOf("", ""), listOf(tagsOf("link"), tagsOf("bill")))
    }

    @Test
    fun whatItDidCanBeReadBack() = runBlocking {
        assertNull(AutoTagUpkeep.lastReport(context))
        store(picture("gpay", "₹250\nPaid to Ravi Kumar\nUPI transaction ID\n627912345678", ""))
        val did = upkeep.retagAll()
        val (at, summary) = assertNotNullAnd(AutoTagUpkeep.lastReport(context))
        assertEquals(did.ranAt, at)
        assertTrue(summary, summary.startsWith("rules v${AutoTags.VERSION}; 1 items checked; 1 re-tagged"))
        assertTrue(summary, summary.endsWith("recognised: payment 1"))
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T { assertNotNull(value); return value!! }
}
