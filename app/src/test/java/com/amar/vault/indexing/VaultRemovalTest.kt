package com.amar.vault.indexing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.HomeWords
import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import com.amar.vault.photos.PhotoVector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Taking something out of the vault takes all of it out — every page, the record, the keyword
 * engine's entries, its place on the import list — and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class VaultRemovalTest {

    private lateinit var db: VaultDatabase
    private val forgottenKeywords = ArrayList<String>()
    private val forgottenFiles = ArrayList<String>()
    private val forgottenVectors = ArrayList<String>()
    private val letGo = ArrayList<String>()
    private val deletedCopies = ArrayList<String>()
    private lateinit var removal: VaultRemoval

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
        removal = VaultRemoval(
            db, forgetKeywords = { forgottenKeywords += it }, forgetFile = { forgottenFiles += it },
            forgetVectors = { forgottenVectors += it }, letGo = { letGo += it }, deleteCopy = { deletedCopies += it },
        )
    }

    @After
    fun close() = db.close()

    private suspend fun document(id: String, pages: Int, uri: String = "content://downloads/$id"): VaultDocument {
        val doc = VaultDocument(id, uri, "$id.pdf", ItemType.PDF, "hash-$id", pageCount = pages, chunkCount = 0, addedAt = 1L)
        IndexPersister(db).persistDocument(doc, (0 until pages).map { i ->
            VaultItem(
                id = "${id}_chunk$i", uri = uri, ocrText = "page ${i + 1} of $id", lang = "en", itemType = ItemType.PDF,
                pageNum = i + 1, sourceFile = doc.name, timestamp = 1L, contentHash = doc.contentHash,
                parentDocumentId = id, chunkIndex = i,
            )
        })
        db.documentImportDao().upsert(
            DocumentImport(id = id, uri = uri, name = doc.name, mimeType = "application/pdf",
                origin = ImportOrigin.PICKED, state = ImportState.DONE, addedAt = 1L)
        )
        return doc
    }

    private suspend fun ids() = db.vaultDao().getAll().map { it.id }.sorted()

    @Test
    fun aDocumentGoesWithEveryPageAndEveryTraceOfIt() = runBlocking {
        document("act", pages = 3)
        document("bill", pages = 2)

        val removed = removal.removeDocument("act")

        assertEquals(VaultRemoval.Removed("act.pdf", 3), removed)
        assertEquals("the other document is untouched", listOf("bill_chunk0", "bill_chunk1"), ids())
        assertNull(db.vaultDocumentDao().getById("act"))
        assertNull(db.documentImportDao().getById("act"))
        assertEquals(listOf("act_chunk0", "act_chunk1", "act_chunk2"), forgottenKeywords.sorted())
        assertEquals("so that adding the file again reads it again", listOf("hash-act"), forgottenFiles)
        assertEquals("nothing opens it from there any more", listOf("content://downloads/act"), letGo)
        assertEquals(0, db.vaultDocumentDao().countPiecesWithoutDocument())
        assertEquals(1, db.vaultDocumentDao().observeCount().first())
    }

    @Test
    fun aPageOfADocumentRemovesTheWholeDocument() = runBlocking {
        document("act", pages = 3)
        val page = db.vaultDao().getByIds(listOf("act_chunk1")).single()

        assertEquals("act.pdf", removal.remove(page)?.name)
        assertEquals(emptyList<String>(), ids())
    }

    // ── What was shared into the app: a row of its own, under the document's id, and a copy ──

    private val copyPath = "/data/user/0/com.amar.vault/files/shared_imports/bill.pdf"

    private fun sharedRow(id: String = "bill", type: ItemType = ItemType.PDF, uri: String = copyPath) = VaultItem(
        id = id, uri = uri, ocrText = "", lang = "en", itemType = type,
        sourceFile = "Bill.pdf", timestamp = 1L, contentHash = "hash-$id", title = "Bill.pdf",
    )

    @Test
    fun aPageOfASharedDocumentRemovesAllOfIt() = runBlocking {
        db.vaultDao().insert(sharedRow())
        document("bill", pages = 2, uri = "file://$copyPath")
        val page = db.vaultDao().getByIds(listOf("bill_chunk1")).single()

        assertEquals(VaultRemoval.Removed("bill.pdf", 3), removal.remove(page))

        assertEquals("no row of it is left to be found by its name", emptyList<String>(), ids())
        assertNull(db.vaultDocumentDao().getById("bill"))
        assertNull(db.documentImportDao().getById("bill"))
        assertEquals(listOf("bill", "bill_chunk0", "bill_chunk1"), forgottenKeywords.sorted())
        assertEquals(listOf(copyPath), deletedCopies)
        assertEquals("start-up repair has nothing to read again", emptyList<VaultItem>(), db.vaultDao().getStandaloneLocalFiles())
    }

    @Test
    fun aSharedDocumentFoundByItsNameRemovesItsPagesToo() = runBlocking {
        val own = sharedRow()
        db.vaultDao().insert(own)
        document("bill", pages = 2, uri = "file://$copyPath")

        removal.remove(own)

        assertEquals(emptyList<String>(), ids())
        assertNull(db.vaultDocumentDao().getById("bill"))
    }

    @Test
    fun theOwnRowAloneWouldLeaveThePagesBehind() = runBlocking {
        // The control: what removing the row by itself does, and why a shared document is not
        // removed that way.
        db.vaultDao().insert(sharedRow())
        document("bill", pages = 2, uri = "file://$copyPath")

        removal.removeItem("bill")

        assertEquals(listOf("bill_chunk0", "bill_chunk1"), ids())
    }

    @Test
    fun aSharedFileStillWaitingToBeReadIsNotReadAfterwards() = runBlocking {
        val own = sharedRow()
        db.vaultDao().insert(own)
        db.documentImportDao().upsert(
            DocumentImport(id = "bill", uri = copyPath, name = "Bill.pdf", mimeType = "application/pdf",
                origin = ImportOrigin.SHARED, state = ImportState.WAITING, addedAt = 1L)
        )

        assertEquals("Bill.pdf", removal.remove(own)?.name)

        assertEquals(emptyList<String>(), ids())
        assertNull(db.documentImportDao().getById("bill"))
        assertEquals(listOf(copyPath), deletedCopies)
    }

    @Test
    fun aSharedLinkIsRemovedByItself() = runBlocking {
        val link = sharedRow("link", ItemType.LINK, "https://example.org/a-long-read")
        db.vaultDao().insert(link)
        document("act", pages = 1)

        removal.remove(link)

        assertEquals(listOf("act_chunk0"), ids())
    }

    @Test
    fun onlyTheAppsOwnCopyIsDeleted() {
        val filesDir = ApplicationProvider.getApplicationContext<Context>().filesDir
        val folder = java.io.File(filesDir, "shared_imports").apply { mkdirs() }
        val copy = java.io.File(folder, "bill.pdf").apply { writeText("x") }
        val other = java.io.File(filesDir, "keep.pdf").apply { writeText("x") }

        assertFalse(SharedCopy.delete(filesDir, other.absolutePath))
        assertFalse("not by a way round", SharedCopy.delete(filesDir, java.io.File(folder, "../keep.pdf").path))
        assertFalse(SharedCopy.delete(filesDir, "content://media/external/images/media/1"))
        assertFalse(SharedCopy.delete(filesDir, "https://example.org/shared_imports/bill.pdf"))
        assertTrue(other.exists())

        assertTrue(SharedCopy.delete(filesDir, "file://" + copy.absolutePath))
        assertFalse(copy.exists())
    }

    @Test
    fun theWordsSayWhatIsBeingRemoved() {
        assertTrue(RemovalWords.whatHappens(isDocument = true, isPicture = false).contains("add it again"))
        assertTrue(RemovalWords.whatHappens(isDocument = false, isPicture = true).contains("picture"))
        assertFalse("a link is not a picture", RemovalWords.whatHappens(isDocument = false, isPicture = false).contains("picture"))
    }

    @Test
    fun aFileTwoDocumentsOpenFromIsKeptReadable() = runBlocking {
        document("first", pages = 1, uri = "content://downloads/shared")
        document("second", pages = 1, uri = "content://downloads/shared")

        removal.removeDocument("first")
        assertEquals("the second still opens from it", emptyList<String>(), letGo)
        removal.removeDocument("second")
        assertEquals(listOf("content://downloads/shared"), letGo)
    }

    @Test
    fun aPictureGoesWithWhatHangsOffIt() = runBlocking {
        val shot = VaultItem(id = "shot", uri = "content://media/1", ocrText = "paid 450 to ravi", lang = "en",
            itemType = ItemType.SCREENSHOT, sourceFile = "Screenshot_1.png", timestamp = 1L)
        db.vaultDao().insert(shot)
        document("act", pages = 1)
        // What the picture looks like, and what another picture looks like.
        for (id in listOf("shot", "beach")) {
            db.photoVectorDao().upsert(PhotoVector(id, "m1", 0L, byteArrayOf(1, 2), 0.01f, 1L))
        }

        assertEquals(VaultRemoval.Removed("Screenshot_1.png", 1), removal.remove(shot))

        assertEquals(listOf("act_chunk0"), ids())
        assertEquals(listOf("shot"), forgottenKeywords)
        assertEquals(listOf("shot"), forgottenVectors)
        assertEquals(0, db.vaultDao().observePictureCount().first())
        assertEquals("only the removed picture's", listOf("beach"), db.photoVectorDao().allOf("m1").map { it.itemId })
    }

    @Test
    fun removingWhatIsAlreadyGoneIsNothing() = runBlocking {
        assertNull(removal.removeDocument("never-there"))
        assertNull(removal.removeItem("never-there"))
        assertTrue(forgottenKeywords.isEmpty())
    }

    // ── What Home says about the vault ──────────────────────────────────────────────────

    @Test
    fun homeCountsWhatCanBeSearched() {
        assertEquals("Nothing in your vault yet", HomeWords.summary(0, 0))
        assertEquals("1 document you can search", HomeWords.summary(1, 0))
        assertEquals("12 pictures you can search", HomeWords.summary(0, 12))
        assertEquals("18 documents and 1 picture you can search", HomeWords.summary(18, 1))
    }

    @Test
    fun homeSaysWhenADocumentWasAdded() {
        val now = 10L * 24 * 60 * 60 * 1000
        val minute = 60_000L
        assertEquals("Added just now · 1 page", HomeWords.added(now - 20_000, 1, now))
        assertEquals("Added 5 min ago · 40 pages", HomeWords.added(now - 5 * minute, 40, now))
        assertEquals("Added 4 hr ago", HomeWords.added(now - 4 * 60 * minute, null, now))
        assertEquals("Added yesterday", HomeWords.added(now - 30 * 60 * minute, null, now))
        assertEquals("Added 3 days ago", HomeWords.added(now - 3 * 24 * 60 * minute, null, now))
    }
}
