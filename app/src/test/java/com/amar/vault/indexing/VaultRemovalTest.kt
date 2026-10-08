package com.amar.vault.indexing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.HomeWords
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
    private lateinit var removal: VaultRemoval

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
        removal = VaultRemoval(
            db, forgetKeywords = { forgottenKeywords += it }, forgetFile = { forgottenFiles += it },
            forgetVectors = { forgottenVectors += it }, letGo = { letGo += it },
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

    @Test
    fun theButtonThatDidNothingLeftEverythingInPlace() = runBlocking {
        // The control: all "Delete Item" did for a document that was never saved — delete a
        // Saved entry by an id no Saved entry has.
        document("act", pages = 3)
        db.stashItemDao().deleteById("search_act_chunk0")

        assertEquals(3, ids().size)
        assertTrue(db.vaultDocumentDao().getById("act") != null)
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
        db.stashItemDao().insertOrUpdate(StashItem(id = "stash", vaultItemId = "shot", category = "Bills", savedAt = 1L, sourceApp = ""))
        document("act", pages = 1)

        assertEquals(VaultRemoval.Removed("Screenshot_1.png", 1), removal.remove(shot))

        assertEquals(listOf("act_chunk0"), ids())
        assertEquals(listOf("shot"), forgottenKeywords)
        assertEquals(listOf("shot"), forgottenVectors)
        assertEquals(0, db.vaultDao().observePictureCount().first())
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
