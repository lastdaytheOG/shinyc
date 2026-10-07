package com.amar.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.V13Vault.item
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening a version-13 vault with this build: every item keeps its id and stays searchable,
 * its tags and its type have moved to where version 14 keeps them, each document has its
 * record, and nothing that hangs off an item — a Saved entry, extracted facts — is lost.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Migration13To14Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "vault-migration-test.db"
    private val nl = 10.toChar()
    private var opened: VaultDatabase? = null
    private var report: MigrationReport? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(name)
    }

    /** Opens the version-13 file with this build, which migrates it. */
    private fun migrate(): VaultDatabase =
        Room.databaseBuilder(context, VaultDatabase::class.java, name)
            .addMigrations(Migration13To14 { report = it })
            .allowMainThreadQueries()
            .build()
            .also { opened = it; it.openHelper.writableDatabase }

    private val actPage1 = "THE FINANCE ACT, 2026$nl(NO. 4 OF 2026)$nl[30th March, 2026]${nl}An Act to give effect to the financial proposals"
    private val actPage1b = "(iii) where the total income$nl[excluding dividend income${nl}or short-term capital gains"
    private val buttons = "Delete this conversation?$nl[Cancel] [Delete]"
    private val upi = "upi://pay?pa=ravi@okaxis&pn=Ravi Kumar"

    private fun SQLiteDatabase.stash(id: String, item: String, folder: String) = insertOrThrow("stash_items", null, ContentValues().apply {
        put("id", id); put("vaultItemId", item); put("vaultType", "SAVED"); put("category", folder); put("savedAt", 7L)
        put("sourceApp", "chrome"); put("isFavorite", 1); put("createdAt", 7L); put("userNote", "keep this")
    })

    private fun SQLiteDatabase.fact(item: String, type: String, value: String) = insertOrThrow("vault_metadata", null, ContentValues().apply {
        put("vaultItemId", item); put("type", type); put("value", value); put("confidence", 1.0)
        put("source", "system"); put("extractionVersion", "v3")
    })

    /** A vault with one of everything version 13 could hold. */
    private fun aVersion13Vault() {
        val db = V13Vault.create(context, name)
        // A PDF picked from the phone's files: pieces only, several to a page.
        db.item("act_chunk0", "$actPage1$nl[pdf document tax government]", "pdf", uri = "content://docs/act",
            sourceFile = "Act.pdf", timestamp = 100, parent = "act", chunkIndex = 0, pageNum = 1, contentHash = "h-act")
        db.item("act_chunk1", "$actPage1b$nl[pdf document financial payment monetary]", "pdf", uri = "content://docs/act",
            sourceFile = "Act.pdf", timestamp = 101, parent = "act", chunkIndex = 1, pageNum = 1, contentHash = "h-act")
        db.item("act_chunk2", "a plain page$nl[pdf document]", "pdf", uri = "content://docs/act",
            sourceFile = "Act.pdf", timestamp = 102, parent = "act", chunkIndex = 2, pageNum = 2, contentHash = "h-act")
        // A PDF shared into the vault: the saved item, and its pieces under the same id.
        db.item("bill", "", "PDF", uri = "/data/user/0/com.amar.vault/files/shared_imports/abc.pdf", sourceFile = "Bill.pdf",
            timestamp = 50, contentHash = "h-bill", mimeType = "application/pdf", title = "Bill.pdf", sharedAt = 50)
        db.stash("stash-bill", "bill", "Bills")
        db.item("bill_chunk0", "amount due in March$nl[pdf document financial payment monetary]", "pdf",
            uri = "file:///data/user/0/com.amar.vault/files/shared_imports/abc.pdf", sourceFile = "Bill.pdf",
            timestamp = 60, parent = "bill", chunkIndex = 0, pageNum = 1, contentHash = "h-bill")
        // A Word file: no pages, its pieces are numbered.
        db.item("notes_chunk0", "minutes of the first meeting$nl[word document meeting minutes notes]", "word",
            uri = "content://docs/notes", sourceFile = "notes.docx", timestamp = 70, parent = "notes", chunkIndex = 0, contentHash = "h-notes")
        db.item("notes_chunk1", "nothing else$nl[word document]", "word", uri = "content://docs/notes",
            sourceFile = "notes.docx", timestamp = 71, parent = "notes", chunkIndex = 1, pageNum = 1, contentHash = "h-notes")
        // Pictures, each named the way the place that indexed it named them.
        db.item("shot", "paid rs 250 to ravi$nl[receipt payment bill invoice]", "screenshot", uri = "content://media/external/images/media/1")
        db.item("camera", "sunset over the lake", "camera", uri = "content://media/external/images/media/2")
        db.item("whatsapp", buttons, "whatsapp", uri = "content://media/external/images/media/3")
        db.item("dev", "18:09 Now playing ELLA CIAO", "dev_manual", uri = "content://media/external/images/media/4")
        db.fact("dev", "SOURCE_TYPE", "DEV_MANUAL")
        db.fact("dev", "DOCUMENT_CLASS", "UNKNOWN")
        db.item("qr", "Scan to pay${nl}Ravi Kumar ravi@okaxis upi payment qr scanner$nl[receipt payment bill invoice qr_data:$upi]",
            "photo", uri = "content://media/external/images/media/5")
        db.item("sharedShot", "", "SCREENSHOT", uri = "/data/user/0/com.amar.vault/files/shared_imports/s.png",
            sourceFile = "Screenshot_1.png", mimeType = "image/png", sharedAt = 9)
        // Saved links and other shared things.
        db.item("video", "", "YOUTUBE", uri = "https://youtu.be/abc", title = "A talk", sharedAt = 10)
        db.stash("stash-video", "video", "")
        db.item("note", "remember the milk", "TEXT", uri = "share://text/1", sharedAt = 11)
        db.item("wordFile", "", "DOCUMENT", uri = "/data/user/0/com.amar.vault/files/shared_imports/x.docx", sourceFile = "x.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document", sharedAt = 12)
        db.item("archive", "", "DOCUMENT", uri = "/data/user/0/com.amar.vault/files/shared_imports/b.zip", sourceFile = "b.zip",
            mimeType = "application/zip", sharedAt = 13)
        db.item("unlisted", "a forwarded picture", "telegram", uri = "content://media/external/images/media/9")
        db.close()
    }

    private class Stored(val text: String, val tags: String, val type: String, val qr: String?)

    private fun VaultDatabase.stored(): Map<String, Stored> {
        val rows = LinkedHashMap<String, Stored>()
        openHelper.readableDatabase.query("SELECT id, ocrText, tags, itemType, qrPayload FROM vault_items ORDER BY rowid").use { c ->
            while (c.moveToNext()) rows[c.getString(0)] = Stored(c.getString(1), c.getString(2), c.getString(3), if (c.isNull(4)) null else c.getString(4))
        }
        return rows
    }

    private val everyId = listOf(
        "act_chunk0", "act_chunk1", "act_chunk2", "bill", "bill_chunk0", "notes_chunk0", "notes_chunk1", "shot", "camera",
        "whatsapp", "dev", "qr", "sharedShot", "video", "note", "wordFile", "archive", "unlisted",
    )

    @Test
    fun everyItemIsStillThereUnderItsId() {
        aVersion13Vault()
        assertEquals(everyId, migrate().stored().keys.toList())
    }

    @Test
    fun tagsLeaveTheTextAndThePageIsWhole() {
        aVersion13Vault()
        val rows = migrate().stored()

        assertEquals("the page keeps the line that starts with a bracket", actPage1, rows.getValue("act_chunk0").text)
        assertEquals("pdf document tax government", rows.getValue("act_chunk0").tags)
        assertEquals(actPage1b, rows.getValue("act_chunk1").text)
        assertEquals("pdf document financial payment monetary", rows.getValue("act_chunk1").tags)
        assertEquals("a plain page", rows.getValue("act_chunk2").text)
        assertEquals("paid rs 250 to ravi", rows.getValue("shot").text)
        assertEquals("receipt payment bill invoice", rows.getValue("shot").tags)

        assertEquals("a row with no tags is left as it was", "sunset over the lake" to "", rows.getValue("camera").let { it.text to it.tags })
        assertEquals("a page that ends with a bracketed line of its own keeps it", buttons, rows.getValue("whatsapp").text)
        assertEquals("", rows.getValue("whatsapp").tags)
        assertTrue("no text ends with a line of tags any more", rows.values.none { FormerStoredText.split(it.text).tags.isNotEmpty() })
    }

    @Test
    fun whatAQrCodeHeldHasItsOwnColumn() {
        aVersion13Vault()
        val rows = migrate().stored()
        assertEquals("Scan to pay${nl}Ravi Kumar ravi@okaxis upi payment qr scanner", rows.getValue("qr").text)
        assertEquals("receipt payment bill invoice", rows.getValue("qr").tags)
        assertEquals(listOf(upi), QrPayloads.split(rows.getValue("qr").qr))
        assertTrue("and no other row has one", rows.filterKeys { it != "qr" }.values.all { it.qr == null })
    }

    @Test
    fun everyTypeIsOneOfTheList() {
        aVersion13Vault()
        val types = migrate().stored().mapValues { it.value.type }
        assertEquals(
            mapOf(
                "act_chunk0" to "pdf", "act_chunk1" to "pdf", "act_chunk2" to "pdf", "bill" to "pdf", "bill_chunk0" to "pdf",
                "notes_chunk0" to "word", "notes_chunk1" to "word",
                "shot" to "screenshot", "camera" to "photo", "whatsapp" to "photo", "dev" to "photo", "qr" to "photo",
                "sharedShot" to "screenshot", "video" to "link", "note" to "text",
                "wordFile" to "word", "archive" to "file", "unlisted" to "photo",
            ),
            types,
        )
        assertTrue(types.values.all { ItemType.ofStored(it) != null })
    }

    @Test
    fun nothingElseAboutARowChanges() = runBlocking {
        aVersion13Vault()
        val db = migrate()
        val piece = db.vaultDao().getByIds(listOf("act_chunk1")).single()
        assertEquals(
            VaultItem(
                id = "act_chunk1", uri = "content://docs/act", ocrText = actPage1b, lang = "en", itemType = ItemType.PDF,
                pageNum = 1, sourceFile = "Act.pdf", timestamp = 101, tags = "pdf document financial payment monetary",
                contentHash = "h-act", parentDocumentId = "act", chunkIndex = 1, totalChunks = 0,
            ),
            piece,
        )
        val saved = db.vaultDao().getByIds(listOf("bill")).single()
        assertEquals("/data/user/0/com.amar.vault/files/shared_imports/abc.pdf", saved.uri)
        assertEquals(listOf("Bill.pdf", "application/pdf", 50L, null), listOf(saved.title, saved.mimeType, saved.sharedAt, saved.parentDocumentId))
    }

    @Test
    fun eachDocumentHasItsRecord() = runBlocking {
        aVersion13Vault()
        val db = migrate()
        val documents = db.vaultDocumentDao().getAll().associateBy { it.id }

        assertEquals(setOf("act", "bill", "notes"), documents.keys)
        assertEquals(
            VaultDocument(id = "act", uri = "content://docs/act", name = "Act.pdf", itemType = ItemType.PDF,
                contentHash = "h-act", pageCount = null, chunkCount = 3, addedAt = 100),
            documents.getValue("act"),
        )
        // The shared one is recorded under the id of its saved item, with its pieces' address.
        assertEquals("file:///data/user/0/com.amar.vault/files/shared_imports/abc.pdf", documents.getValue("bill").uri)
        assertEquals(1, documents.getValue("bill").chunkCount)
        assertEquals(ItemType.WORD to 2, documents.getValue("notes").let { it.itemType to it.chunkCount })
        assertEquals("no piece is without its document", 0, db.vaultDocumentDao().countPiecesWithoutDocument())
    }

    @Test
    fun savedEntriesAndExtractedFactsAreUntouched() = runBlocking {
        aVersion13Vault()
        val db = migrate()

        val saved = db.stashItemDao().getStashItemsByType("SAVED").first().associateBy { it.stashId }
        assertEquals(setOf("stash-bill", "stash-video"), saved.keys)
        assertEquals(listOf("Bills", "keep this", true), saved.getValue("stash-bill").let { listOf(it.category, it.userNote, it.isFavorite) })
        assertEquals(ItemType.PDF, saved.getValue("stash-bill").itemType)
        assertEquals(ItemType.LINK, saved.getValue("stash-video").itemType)

        val facts = db.vaultMetadataDao().getByItemIds(listOf("dev")).associate { it.type to it.value }
        assertEquals("a picture's source type is its type again", mapOf("SOURCE_TYPE" to "PHOTO", "DOCUMENT_CLASS" to "UNKNOWN"), facts)
    }

    @Test
    fun theFullTextIndexFollowsTheNewText() = runBlocking {
        // Before: the tags were part of the indexed text.
        aVersion13Vault()
        val before = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
        val taggedBefore = before.rawQuery("SELECT COUNT(*) FROM vault_fts WHERE vault_fts MATCH 'government'", null)
            .use { it.moveToFirst(); it.getInt(0) }
        before.close()
        assertEquals("the control: version 13 indexed the tag as text", 1, taggedBefore)

        val db = migrate()
        suspend fun found(word: String) = db.vaultDao().searchFts(word).first().map { it.id }
        assertEquals("a tag is no longer text", emptyList<String>(), found("government"))
        assertEquals("the page still is, all of it", listOf("act_chunk1"), found("dividend"))
        assertEquals(listOf("act_chunk0"), found("proposals"))

        // And it is kept in step from here on: Room put its triggers back.
        db.vaultDao().insert(VaultItem(id = "new", uri = "content://x/new", ocrText = "a zebra crossing", lang = "en",
            itemType = ItemType.PHOTO, timestamp = 1L))
        assertEquals(listOf("new"), found("zebra"))
    }

    @Test
    fun theMigrationSaysWhatItDid() {
        aVersion13Vault()
        migrate()
        val did = assertNotNullAnd(report)
        assertEquals(18, did.rows)
        assertEquals("six pieces of documents, and two pictures", 8, did.tagLinesMoved)
        assertEquals(1, did.itemsWithQrCodes)
        assertEquals("the page that ends with its own bracketed line", 1, did.bracketEndingsKept)
        assertEquals(3, did.documents)
        assertEquals(
            mapOf("PDF → pdf" to 1, "camera → photo" to 1, "whatsapp → photo" to 1, "dev_manual → photo" to 1,
                "SCREENSHOT → screenshot" to 1, "YOUTUBE → link" to 1, "TEXT → text" to 1, "DOCUMENT → word" to 1,
                "DOCUMENT → file" to 1, "telegram → photo" to 1),
            did.typesRenamed,
        )
    }

    @Test
    fun aLargeVaultIsReadInBatchesAndNoRowIsMissed() {
        val db = V13Vault.create(context, name)
        db.beginTransaction()
        repeat(1_050) { i ->
            db.item("big_chunk$i", "page text number $i$nl[pdf document]", "pdf", uri = "content://docs/big", sourceFile = "Big.pdf",
                timestamp = i.toLong(), parent = "big", chunkIndex = i, pageNum = i / 3 + 1, contentHash = "h-big")
        }
        db.setTransactionSuccessful(); db.endTransaction(); db.close()

        val rows = migrate().stored()
        assertEquals(1_050, rows.size)
        assertTrue(rows.all { (id, row) -> row.text == "page text number ${id.removePrefix("big_chunk")}" && row.tags == "pdf document" })
        assertEquals(1_050, report!!.tagLinesMoved)
        assertEquals(1_050, runBlocking { opened!!.vaultDocumentDao().getById("big")!!.chunkCount })
    }

    @Test
    fun anEmptyVaultMigrates() {
        V13Vault.create(context, name).close()
        val db = migrate()
        assertTrue(db.stored().isEmpty())
        assertEquals(0, report!!.rows)
        assertFalse(db.openHelper.readableDatabase.query("SELECT 1 FROM documents").use { it.moveToFirst() })
    }

    @Test
    fun whatItDidCanBeReadBackOnTheDevice() {
        assertNull(MigrationReport.load(context))
        val did = MigrationReport(ranAt = 1234L, rows = 5, tagLinesMoved = 3, itemsWithQrCodes = 0, bracketEndingsKept = 0,
            typesRenamed = sortedMapOf("PDF → pdf" to 1), documents = 2)
        MigrationReport.save(context, did)
        assertEquals(1234L to did.summary(), MigrationReport.load(context))
        assertTrue(did.summary(), did.summary().startsWith("5 items checked; 3 tag lines moved out of the text"))
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T { assertNotNull(value); return value!! }
}
