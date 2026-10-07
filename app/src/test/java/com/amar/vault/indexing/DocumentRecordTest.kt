package com.amar.vault.indexing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.ItemType
import com.amar.vault.StashItem
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A document and its pieces as [IndexPersister] writes them: the document has one record, a
 * piece is never stored without it, and the record's count is the number of pieces there are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DocumentRecordTest {

    private lateinit var db: VaultDatabase
    private lateinit var persister: IndexPersister

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
        persister = IndexPersister(db)
    }

    @After
    fun close() = db.close()

    private fun document(id: String, hash: String, pages: Int? = null) = VaultDocument(
        id = id, uri = "content://docs/$id", name = "$id.pdf", itemType = ItemType.PDF,
        contentHash = hash, pageCount = pages, chunkCount = 0, addedAt = 10L,
    )

    private fun pieces(of: VaultDocument, range: IntRange) = range.map { i ->
        VaultItem(
            id = "${of.id}_chunk$i", uri = of.uri, ocrText = "piece $i", lang = "en", itemType = of.itemType,
            pageNum = i + 1, sourceFile = of.name, timestamp = 10L + i, tags = "pdf document",
            contentHash = of.contentHash, parentDocumentId = of.id, chunkIndex = i, totalChunks = 0,
        )
    }

    private suspend fun storedPieces(id: String) =
        db.vaultDao().getAll().filter { it.parentDocumentId == id }.map { it.id }.sorted()

    @Test
    fun aDocumentWrittenAllAtOnceHasItsRecordAndItsCount() = runBlocking {
        val act = document("act", "h1", pages = 12)
        persister.persistDocument(act, pieces(act, 0..4))

        assertEquals(act.copy(chunkCount = 5), db.vaultDocumentDao().getById("act"))
        assertEquals(0, db.vaultDocumentDao().countPiecesWithoutDocument())
    }

    @Test
    fun aDocumentWrittenPageByPageIsRecordedBeforeItsFirstPage() = runBlocking {
        val act = document("act", "h1")
        persister.beginDocument(act)
        assertEquals("recorded, with nothing stored yet", 0, db.vaultDocumentDao().getById("act")!!.chunkCount)

        persister.recordPageCount("act", 121)
        persister.appendDocumentChunks("act", pieces(act, 0..2))
        assertEquals(3, db.vaultDocumentDao().getById("act")!!.chunkCount)
        persister.appendDocumentChunks("act", pieces(act, 3..6))

        // Cut short here, the record says how far it got.
        assertEquals(act.copy(pageCount = 121, chunkCount = 7), db.vaultDocumentDao().getById("act"))
        assertEquals(0, db.vaultDocumentDao().countPiecesWithoutDocument())
    }

    @Test
    fun readingAFileAgainStartsItsDocumentAgain() = runBlocking {
        val first = document("act", "h1")
        persister.beginDocument(first)
        persister.appendDocumentChunks("act", pieces(first, 0..3))

        // The same file again, under a new id as a second import gives it.
        val second = document("act-again", "h1")
        persister.beginDocument(second)
        persister.appendDocumentChunks("act-again", pieces(second, 0..1))

        assertNull("the first attempt's record is gone", db.vaultDocumentDao().getById("act"))
        assertEquals(emptyList<String>(), storedPieces("act"))
        assertEquals(2, db.vaultDocumentDao().getById("act-again")!!.chunkCount)
        assertEquals(0, db.vaultDocumentDao().countPiecesWithoutDocument())
    }

    @Test
    fun aNewVersionOfASavedFileLeavesNoPiecesOfTheOldOne() = runBlocking {
        // A shared file keeps its id; indexed first by its text's hash, later by its bytes'.
        val old = document("bill", "text-hash")
        persister.persistDocument(old, pieces(old, 0..5))

        val new = document("bill", "file-hash")
        persister.beginDocument(new)
        persister.appendDocumentChunks("bill", pieces(new, 0..1))

        assertEquals(listOf("bill_chunk0", "bill_chunk1"), storedPieces("bill"))
        assertEquals(new.copy(chunkCount = 2), db.vaultDocumentDao().getById("bill"))
    }

    @Test
    fun withoutStartingTheDocumentAgainTheOldPiecesStayedBehind() = runBlocking {
        // The control for the test above: what writing the new pieces alone did before.
        val old = document("bill", "text-hash")
        persister.persistDocument(old, pieces(old, 0..5))
        db.vaultDao().insertAll(pieces(document("bill", "file-hash"), 0..1))

        assertEquals("four pieces of a version that is gone", 6, storedPieces("bill").size)
    }

    @Test
    fun theSavedItemOfASharedDocumentIsNotOneOfItsPieces() = runBlocking {
        // The saved item has the document's id and its hash, and a Saved entry hangs off it.
        val saved = VaultItem(
            id = "bill", uri = "/files/shared_imports/x.pdf", ocrText = "", lang = "en", itemType = ItemType.PDF,
            sourceFile = "Bill.pdf", timestamp = 1L, contentHash = "h1", sharedAt = 1L, title = "Bill.pdf",
        )
        db.vaultDao().insert(saved)
        db.stashItemDao().insertOrUpdate(StashItem(id = "stash", vaultItemId = "bill", category = "Bills", savedAt = 1L, sourceApp = ""))

        val bill = document("bill", "h1")
        persister.persistDocument(bill, pieces(bill, 0..2))
        persister.beginDocument(bill)                 // read again from the start
        persister.deletePartialByContentHash("h1")    // and repaired

        assertEquals(listOf(saved), db.vaultDao().getAll())
        assertEquals(listOf("stash"), db.stashItemDao().getStashItemsByType("SAVED").first().map { it.stashId })
        assertNull(db.vaultDocumentDao().getById("bill"))
    }
}
