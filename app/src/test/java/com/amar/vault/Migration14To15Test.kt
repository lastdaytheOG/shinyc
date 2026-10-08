package com.amar.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.indexing.DocumentImport
import com.amar.vault.indexing.ImportOrigin
import com.amar.vault.indexing.ImportState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Opening a version-14 vault with this build. Version 15 adds one table, the list of files
 * the vault was asked to read; every row that was there is there afterwards, untouched.
 *
 * The version-14 vault is built from the schema that build exported
 * (`schemas/…/14.json`), so it is that version's database to the letter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Migration14To15Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "vault-14-to-15-test.db"
    private var opened: VaultDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(name)
    }

    /** An empty vault exactly as the version-14 build made it. */
    private fun aVersion14Vault(): SQLiteDatabase {
        val schema = JSONObject(File("schemas/com.amar.vault.VaultDatabase/14.json").readText()).getJSONObject("database")
        assertEquals(14, schema.getInt("version"))
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                for (j in 0 until triggers.length()) db.execSQL(triggers.getString(j))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = 14
        return db
    }

    private fun SQLiteDatabase.item(id: String, text: String, type: String, parent: String? = null, page: Int = 0, hash: String = "") =
        insertOrThrow("vault_items", null, ContentValues().apply {
            put("id", id); put("uri", "content://docs/${parent ?: id}"); put("ocrText", text); put("lang", "en")
            put("itemType", type); put("pageNum", page); put("sourceFile", "Act.pdf"); put("timestamp", 100L); put("pHash", 0L)
            put("tags", "pdf document"); put("contentHash", hash); put("parentDocumentId", parent)
            put("chunkIndex", (page - 1).coerceAtLeast(0)); put("totalChunks", 0)
        })

    private fun aVaultWithThingsInIt() {
        val db = aVersion14Vault()
        db.item("act_chunk0", "THE FINANCE ACT, 2026", "pdf", parent = "act", page = 1, hash = "h-act")
        db.item("act_chunk1", "where the total income exceeds", "pdf", parent = "act", page = 2, hash = "h-act")
        db.insertOrThrow("documents", null, ContentValues().apply {
            put("id", "act"); put("uri", "content://docs/act"); put("name", "Act.pdf"); put("itemType", "pdf")
            put("contentHash", "h-act"); put("pageCount", 121); put("chunkCount", 2); put("addedAt", 100L)
        })
        db.item("shot", "Payment of 450 to Ravi", "screenshot")
        db.insertOrThrow("stash_items", null, ContentValues().apply {
            put("id", "stash"); put("vaultItemId", "shot"); put("vaultType", "SAVED"); put("category", "Bills")
            put("savedAt", 7L); put("sourceApp", "gpay"); put("isFavorite", 1); put("createdAt", 7L); put("userNote", "keep")
        })
        db.close()
    }

    private fun open(vararg migrations: androidx.room.migration.Migration): VaultDatabase =
        Room.databaseBuilder(context, VaultDatabase::class.java, name)
            .addMigrations(*migrations)
            .allowMainThreadQueries()
            .build()
            .also { opened = it; it.openHelper.writableDatabase }

    @Test
    fun everythingStoredIsStillThereAndTheNewListIsEmpty() = runBlocking {
        aVaultWithThingsInIt()

        val db = open(VaultDatabase.MIGRATION_14_15)

        val items = db.vaultDao().getAll().associateBy { it.id }
        assertEquals(setOf("act_chunk0", "act_chunk1", "shot"), items.keys)
        assertEquals("THE FINANCE ACT, 2026", items.getValue("act_chunk0").ocrText)
        assertEquals("pdf document", items.getValue("act_chunk0").tags)
        assertEquals(2, items.getValue("act_chunk1").pdfPage)
        assertEquals(ItemType.SCREENSHOT, items.getValue("shot").itemType)
        assertEquals(
            VaultDocument("act", "content://docs/act", "Act.pdf", ItemType.PDF, "h-act", pageCount = 121, chunkCount = 2, addedAt = 100L),
            db.vaultDocumentDao().getById("act"),
        )
        val saved = db.stashItemDao().getStashItemsByType("SAVED").first().single()
        assertEquals(listOf("shot", "Bills", "keep"), listOf(saved.vaultItemId, saved.category, saved.userNote))

        assertEquals(emptyList<DocumentImport>(), db.documentImportDao().getAll())
    }

    @Test
    fun theFullTextIndexStillFindsWhatWasStored() = runBlocking {
        aVaultWithThingsInIt()
        val db = open(VaultDatabase.MIGRATION_14_15)

        assertEquals(listOf("act_chunk1"), db.vaultDao().searchFts("income").first().map { it.id })
    }

    @Test
    fun theNewListCanBeWrittenAndRead() = runBlocking {
        aVaultWithThingsInIt()
        val db = open(VaultDatabase.MIGRATION_14_15)

        val row = DocumentImport(
            id = "scan", uri = "content://docs/scan", name = "Scan.pdf", mimeType = "application/pdf",
            origin = ImportOrigin.PICKED, state = ImportState.READING, fingerprint = "fp", pagesDone = 120,
            pageCount = 300, pieces = 410, addedAt = 5L,
        )
        db.documentImportDao().upsert(row)
        assertEquals(row, db.documentImportDao().getById("scan"))
        assertEquals(listOf("scan"), db.documentImportDao().unfinished().map { it.id })
    }

    @Test
    fun withoutTheStepThisBuildCannotOpenAVersion14Vault() {
        // The control: Room refuses to open an older database it has no step for, rather than
        // wipe it — there is no destructive fallback in this app.
        aVaultWithThingsInIt()
        try {
            open()
            fail("a version-14 vault was opened with no step to version 15")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), "14" in e.message.orEmpty() && "15" in e.message.orEmpty())
        }
    }

    @Test
    fun aVersion13VaultGoesAllTheWay() = runBlocking {
        V13Vault.create(context, name).close()
        val db = open(*VaultDatabase.migrations())
        assertEquals(emptyList<DocumentImport>(), db.documentImportDao().getAll())
        assertEquals(15, db.openHelper.readableDatabase.version)
    }
}
