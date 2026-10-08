package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A document whose file was moved: the user shows where it is now and it opens again, with
 * nothing copied and nothing read again. A file that is not the one the document was read
 * from is not taken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DocumentRelinkTest {

    private lateinit var db: VaultDatabase

    /** The phone's files: address → the hash of the bytes there. */
    private val files = HashMap<String, String>()
    /** Which bytes the reuse cache remembers as having given which text. */
    private val readAs = HashMap<String, String>()
    /** The text hash of a Word, Excel or EPUB file, as reading it again gives. */
    private val textOf = HashMap<String, String>()
    private val kept = ArrayList<String>()
    private val letGo = ArrayList<String>()
    private var filesReadAgain = 0

    private lateinit var relink: DocumentRelink

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
        relink = DocumentRelink(
            db = db,
            canOpen = { it in files },
            fingerprintOf = { files[it.toString()] },
            completedAs = { readAs[it] },
            textHashOf = { uri, _ -> filesReadAgain++; textOf[uri.toString()] },
            keep = { kept += it.toString() },
            letGo = { letGo += it },
        )
    }

    @After
    fun close() = db.close()

    private suspend fun document(id: String, hash: String, type: ItemType = ItemType.PDF, pages: Int = 3): VaultDocument {
        val ending = if (type == ItemType.PDF) "pdf" else "docx"
        val doc = VaultDocument(
            id = id, uri = "content://downloads/$id", name = "$id.$ending", itemType = type,
            contentHash = hash, pageCount = pages.takeIf { type == ItemType.PDF }, chunkCount = 0, addedAt = 1L,
        )
        IndexPersister(db).persistDocument(doc, (0 until pages).map { i ->
            VaultItem(
                id = "${id}_chunk$i", uri = doc.uri, ocrText = "page ${i + 1} of $id", lang = "en", itemType = type,
                pageNum = i + 1, sourceFile = doc.name, timestamp = 1L, contentHash = hash, parentDocumentId = id, chunkIndex = i,
            )
        })
        files[doc.uri] = hash
        return db.vaultDocumentDao().getById(id)!!
    }

    private fun uri(address: String) = Uri.parse(address)
    private suspend fun pieces(id: String) = db.vaultDao().getAll().filter { it.parentDocumentId == id }.sortedBy { it.chunkIndex }

    @Test
    fun aMovedFileIsFoundAgainAndNothingElseChanges() = runBlocking {
        val act = document("act", hash = "bytes-of-act")
        val before = pieces("act")
        // Moved from Downloads to Documents.
        files.remove(act.uri)
        files["content://documents/act"] = "bytes-of-act"
        assertEquals(listOf("act"), relink.missing().map { it.id })

        val outcome = relink.relink(act, uri("content://documents/act"))

        assertEquals(DocumentRelink.Outcome.Found(act, uri("content://documents/act")), outcome)
        assertEquals("content://documents/act", db.vaultDocumentDao().getById("act")!!.uri)
        assertEquals("every page opens from the new place", setOf("content://documents/act"), pieces("act").map { it.uri }.toSet())
        assertEquals("ids, text, pages and hash are as they were", before.map { it.copy(uri = "") }, pieces("act").map { it.copy(uri = "") })
        assertEquals(emptyList<VaultDocument>(), relink.missing())
        assertEquals("the right to read it outlives the app", listOf("content://documents/act"), kept)
        assertEquals(listOf("content://downloads/act"), letGo)
        assertEquals("no file was read again", 0, filesReadAgain)
    }

    @Test
    fun beforeAMovedFileStayedUnopenableForGood() = runBlocking {
        // The control: with no way to say where the file went, its address led nowhere.
        val act = document("act", hash = "bytes-of-act")
        files.remove(act.uri)
        files["content://documents/act"] = "bytes-of-act"

        assertEquals(listOf("act"), relink.missing().map { it.id })
        assertEquals("content://downloads/act", db.vaultDocumentDao().getById("act")!!.uri)
        assertEquals("…though its text was there all along", 3, pieces("act").size)
    }

    @Test
    fun anotherFileIsNotTaken() = runBlocking {
        val act = document("act", hash = "bytes-of-act")
        files.remove(act.uri)
        files["content://documents/other"] = "bytes-of-something-else"

        assertEquals(DocumentRelink.Outcome.AnotherFile, relink.relink(act, uri("content://documents/other")))

        assertEquals("content://downloads/act", db.vaultDocumentDao().getById("act")!!.uri)
        assertEquals(setOf("content://downloads/act"), pieces("act").map { it.uri }.toSet())
        assertTrue(kept.isEmpty())
        assertEquals(listOf("act"), relink.missing().map { it.id })
    }

    @Test
    fun aFileThatCannotBeReadIsSaidToBeSo() = runBlocking {
        val act = document("act", hash = "bytes-of-act")
        assertEquals(DocumentRelink.Outcome.CannotRead, relink.relink(act, uri("content://nowhere/x")))
    }

    @Test
    fun aPdfStoredUnderItsTextsHashIsKnownByTheBytesThatGaveThatText() = runBlocking {
        // Read whole, before PDFs were read page by page: stored under the hash of its text.
        val old = document("old", hash = "text-hash")
        files.remove(old.uri)
        files["content://documents/old"] = "its-bytes"
        readAs["its-bytes"] = "text-hash"

        assertTrue(relink.relink(old, uri("content://documents/old")) is DocumentRelink.Outcome.Found)
        assertEquals(0, filesReadAgain)
    }

    @Test
    fun aWordFileIsKnownByItsText() = runBlocking {
        val notes = document("notes", hash = "text-of-notes", type = ItemType.WORD)
        files.remove(notes.uri)
        files["content://documents/notes"] = "bytes"
        files["content://documents/letter"] = "other bytes"
        textOf["content://documents/notes"] = "text-of-notes"
        textOf["content://documents/letter"] = "text-of-a-letter"

        assertEquals(DocumentRelink.Outcome.AnotherFile, relink.relink(notes, uri("content://documents/letter")))
        assertTrue(relink.relink(notes, uri("content://documents/notes")) is DocumentRelink.Outcome.Found)
    }

    @Test
    fun severalMissingFilesAreFoundFromOneChoice() = runBlocking {
        val act = document("act", hash = "a")
        val bill = document("bill", hash = "b")
        val lease = document("lease", hash = "c")
        // The whole folder was moved; the lease was deleted.
        listOf(act, bill, lease).forEach { files.remove(it.uri) }
        files["content://moved/1"] = "b"
        files["content://moved/2"] = "something else"
        files["content://moved/3"] = "a"

        val found = relink.relinkAll(relink.missing(), listOf("content://moved/1", "content://moved/2", "content://moved/3").map(::uri))

        assertEquals(mapOf("bill" to "content://moved/1", "act" to "content://moved/3"), found.associate { it.document.id to it.uri.toString() })
        assertEquals(listOf("lease"), relink.missing().map { it.id })
    }

    @Test
    fun aResultListStillOnScreenOpensTheFileWhereItIsNow() = runBlocking {
        val act = document("act", hash = "bytes-of-act")
        files.remove(act.uri)
        files["content://documents/act"] = "bytes-of-act"
        relink.relink(act, uri("content://documents/act"))

        assertEquals("content://documents/act", DocumentRelink.currentAddress("content://downloads/act"))
        assertEquals("an address that never moved is itself", "content://x/y", DocumentRelink.currentAddress("content://x/y"))
    }

    @Test
    fun aDocumentSharedInIsOpenedFromTheAppsOwnCopyAndIsNotMissing() = runBlocking {
        val real = java.io.File.createTempFile("shared", ".pdf")
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            // As capture stores it: a bare path from the root (no drive letter, on a PC).
            val barePath = real.absolutePath.replace(92.toChar(), '/').substringAfter(':')
            assertTrue(barePath, DocumentRelink.canOpen(context, barePath))
            // And as the indexer is handed it. (Built by hand: on a PC, Uri.fromFile puts the drive letter in the wrong part.)
            val withScheme = "file://$barePath"
            assertTrue(withScheme, DocumentRelink.canOpen(context, withScheme))
            assertTrue("a link is not a file", DocumentRelink.canOpen(context, "https://example.com/a.pdf"))
            real.delete()
            assertTrue("gone: $barePath", !DocumentRelink.canOpen(context, barePath))
            assertTrue("gone: $withScheme", !DocumentRelink.canOpen(context, withScheme))
        } finally {
            real.delete()
        }
    }
}
