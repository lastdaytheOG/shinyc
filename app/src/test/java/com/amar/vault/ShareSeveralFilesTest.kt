package com.amar.vault

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sharing several files at once: each one is saved. Until 2026-10 the capture kept the one
 * it judged the most important and dropped the rest, after copying all of them into the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ShareSeveralFilesTest {

    private lateinit var db: VaultDatabase
    private val resolver = ContentPriorityResolver()
    private val session = IngestionSession(
        id = "s", sourceType = SourceType.ANDROID_SHARE, action = "android.intent.action.SEND_MULTIPLE",
        type = "*/*", sourcePackage = "com.google.android.apps.docs", timestamp = 5L, status = SessionStatus.CAPTURING,
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun file(name: String, mime: String, hash: String = "hash-$name") = IngestionAttachment(
        id = "att-$name", sessionId = "s", status = AttachmentStatus.READY, errorCode = AttachmentError.NONE,
        attachmentType = "STREAM", originalUri = "content://media/$name", localPath = "/data/files/shared_imports/$name",
        mimeType = mime, filename = name, contentHash = hash,
        width = null, height = null, duration = null, fileSize = 1000L, pageCount = null, domain = null,
        artist = null, album = null, latitude = null, longitude = null, thumbnailPath = null,
        previewTitle = name, rawExtrasJson = null,
    )

    private fun text(value: String) = file("text", "text/plain", hash = "hash-text").copy(
        id = "att-text", attachmentType = "TEXT", originalUri = value, localPath = null, filename = null, previewTitle = null,
    )

    /** Saves a share the way the capture does, inside its transaction. */
    private suspend fun save(attachments: List<IngestionAttachment>, folder: String = "Bills"): List<IngestionAttachment> {
        val toSave = SharedFiles.toSave(resolver.resolve(attachments).primaryAttachment, attachments)
        db.withTransaction {
            db.ingestionSessionDao().insert(session)
            toSave.forEachIndexed { position, attachment ->
                ShareCaptureManager.saveOne(
                    db, session, attachment, resolver.resolve(listOf(attachment)), folder, userNote = "tax 2026", at = 100L + position,
                )
            }
        }
        return toSave
    }

    private suspend fun saved() = db.stashItemDao().getStashItemsByType("SAVED").first()

    @Test
    fun everyFileOfAShareBecomesAnItemWithItsOwnSavedEntry() = runBlocking {
        save(listOf(
            file("Rent.pdf", "application/pdf"), file("Power.pdf", "application/pdf"),
            file("Receipt.jpg", "image/jpeg"), file("Budget.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        ))

        val items = db.vaultDao().getAll().sortedBy { it.timestamp }
        assertEquals(listOf("Rent.pdf", "Power.pdf", "Receipt.jpg", "Budget.xlsx"), items.map { it.sourceFile })
        assertEquals(listOf(ItemType.PDF, ItemType.PDF, ItemType.PHOTO, ItemType.EXCEL), items.map { it.itemType })
        assertEquals("each is opened from its own copy", 4, items.map { it.uri }.toSet().size)

        val entries = saved()
        assertEquals(items.map { it.id }.toSet(), entries.map { it.vaultItemId }.toSet())
        assertEquals(setOf("Bills"), entries.map { it.category }.toSet())
        assertEquals(setOf("tax 2026"), entries.map { it.userNote }.toSet())
    }

    @Test
    fun onlyTheFirstWasKeptBefore() {
        // The control: what the capture saved until 2026-10 — the resolver's pick, and nothing else.
        val shared = listOf(
            file("Rent.pdf", "application/pdf"), file("Power.pdf", "application/pdf"), file("Receipt.jpg", "image/jpeg"),
        )
        val before = listOf(resolver.resolve(shared).primaryAttachment)
        assertEquals(listOf("Rent.pdf"), before.map { it.filename })
        assertEquals(3, SharedFiles.toSave(before.single(), shared).size)
    }

    @Test
    fun oneFileIsSavedExactlyAsItWas() = runBlocking {
        val toSave = save(listOf(file("Rent.pdf", "application/pdf")))

        assertEquals(1, toSave.size)
        val item = db.vaultDao().getAll().single()
        assertEquals("/data/files/shared_imports/Rent.pdf", item.uri)
        assertEquals("hash-Rent.pdf", item.contentHash)
        assertEquals("Rent.pdf", item.title)
        assertEquals(5L, item.sharedAt)
        assertEquals(1, saved().size)
    }

    @Test
    fun aLinkSharedOnItsOwnIsStillOneItem() = runBlocking {
        save(listOf(text("Watch this https://youtu.be/abc123")))

        val item = db.vaultDao().getAll().single()
        assertEquals(ItemType.LINK, item.itemType)
        assertEquals("Watch this https://youtu.be/abc123", item.originalUri)
    }

    @Test
    fun aCaptionThatComesWithFilesIsNotAnItemOfItsOwn() = runBlocking {
        save(listOf(text("Minutes of the meeting"), file("Minutes.pdf", "application/pdf"), file("Annex.pdf", "application/pdf")))

        assertEquals(listOf("Annex.pdf", "Minutes.pdf"), db.vaultDao().getAll().map { it.sourceFile }.sorted())
    }

    @Test
    fun aLinkSharedWithFilesKeepsTheLinkAndTheFiles() = runBlocking {
        // The link is what the resolver picks; before, the file that came with it was dropped.
        save(listOf(text("https://example.com/report"), file("Report.pdf", "application/pdf")))

        assertEquals(listOf(ItemType.LINK, ItemType.PDF), db.vaultDao().getAll().sortedBy { it.timestamp }.map { it.itemType })
    }

    @Test
    fun theSameFileTwiceInOneShareIsSavedOnce() = runBlocking {
        save(listOf(
            file("Rent.pdf", "application/pdf", hash = "same"),
            file("Rent (1).pdf", "application/pdf", hash = "same"),
            file("Power.pdf", "application/pdf"),
        ))

        assertEquals(2, db.vaultDao().getAll().size)
        assertEquals(2, saved().size)
    }

    @Test
    fun aFileThatIsAlreadyInTheVaultIsNotStoredTwice() = runBlocking {
        save(listOf(file("Rent.pdf", "application/pdf")))
        val first = db.vaultDao().getAll().single().id

        save(listOf(file("Rent.pdf", "application/pdf"), file("Power.pdf", "application/pdf")))

        val items = db.vaultDao().getAll()
        assertEquals(2, items.size)
        assertEquals("the item keeps its id", first, items.single { it.sourceFile == "Rent.pdf" }.id)
    }
}
