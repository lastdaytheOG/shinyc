package com.amar.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * A shared PDF is one saved row plus one row per chunk, and every one of them carries the
 * file's hash. The queries that work by that hash must each mean one kind of row: indexing a
 * shared PDF clears "the rows with this hash" first, and that must never take the saved item
 * (and, through the foreign key, its Stash entry) with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SharedDocumentRowsTest {

    private lateinit var db: VaultDatabase
    private lateinit var vaultDao: VaultDao

    private val hash = "af6b6d9dc0f2"
    private val saved = VaultItem(
        id = "saved", uri = "/data/user/0/com.amar.vault/files/shared_imports/abc.pdf", ocrText = "",
        lang = "en", itemType = ItemType.PDF, sourceFile = "Bill.pdf", timestamp = 1L, contentHash = hash,
        title = "Bill.pdf", mimeType = "application/pdf",
    )
    private val chunks = (0 until 3).map {
        VaultItem(
            id = "saved_chunk$it", uri = "file:///data/user/0/com.amar.vault/files/shared_imports/abc.pdf",
            ocrText = "page $it", lang = "en", itemType = ItemType.PDF, pageNum = it + 1, sourceFile = "Bill.pdf",
            timestamp = 2L, contentHash = hash, parentDocumentId = "saved", chunkIndex = it,
        )
    }

    @Before
    fun createDb() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        vaultDao = db.vaultDao()
        vaultDao.insert(saved)
        db.stashItemDao().insertOrUpdate(
            StashItem(id = "stash", vaultItemId = "saved", category = "Bills", savedAt = 1L, sourceApp = "")
        )
        vaultDao.insertAll(chunks)
    }

    @After
    fun closeDb() = db.close()

    @Test
    fun onlyChunksAreCounted() = runBlocking {
        assertEquals(3, vaultDao.countChunksByContentHash(hash))
    }

    @Test
    fun theItemWithAHashIsTheSavedItemNeverAChunk() = runBlocking {
        assertEquals("saved", vaultDao.findByContentHash(hash)?.id)
    }

    @Test
    fun clearingADocumentsChunksKeepsTheSavedItemAndItsStashEntry() = runBlocking {
        vaultDao.deleteChunksByContentHash(hash)

        assertEquals(listOf("saved"), vaultDao.getAll().map { it.id })
        assertEquals(listOf("stash"), db.stashItemDao().getStashItemsByType("SAVED").first().map { it.stashId })
    }

    @Test
    fun aDocumentThatWasOnlyImportedHasNoItemOfItsOwn() = runBlocking {
        val imported = chunks.map { it.copy(id = "doc_${it.chunkIndex}", parentDocumentId = "doc", contentHash = "other") }
        vaultDao.insertAll(imported)
        assertNull(vaultDao.findByContentHash("other"))
        assertEquals(3, vaultDao.countChunksByContentHash("other"))
    }

    @Test
    fun repairLooksAtSavedLocalFilesOnly() = runBlocking {
        vaultDao.insert(
            VaultItem(id = "link", uri = "https://example.com/a.pdf", ocrText = "", lang = "en", itemType = ItemType.LINK, timestamp = 3L)
        )
        assertEquals(listOf("saved"), vaultDao.getStandaloneLocalFiles().map { it.id })
    }
}
