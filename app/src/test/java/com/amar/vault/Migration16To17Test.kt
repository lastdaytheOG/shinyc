package com.amar.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.photos.PhotoVector
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Opening a version-16 vault with this build. Version 17 drops the Saved screen's table, with the
 * folders, notes and favourites in it. Everything that was saved is a row of `vault_items`, and
 * stays: it can still be found and opened.
 *
 * The version-16 vault is built from the schema that build exported (`schemas/…/16.json`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Migration16To17Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "vault-16-to-17-test.db"
    private var opened: VaultDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(name)
    }

    /** An empty vault exactly as the version-16 build made it. */
    private fun aVersion16Vault(): SQLiteDatabase {
        val schema = JSONObject(File("schemas/com.amar.vault.VaultDatabase/16.json").readText()).getJSONObject("database")
        assertEquals(16, schema.getInt("version"))
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
        db.version = 16
        return db
    }

    private fun SQLiteDatabase.item(id: String, text: String, type: String) =
        insertOrThrow("vault_items", null, ContentValues().apply {
            put("id", id); put("uri", "/files/shared_imports/$id"); put("ocrText", text); put("lang", "en")
            put("itemType", type); put("pageNum", 0); put("sourceFile", id); put("timestamp", 100L); put("pHash", 11L)
            put("tags", ""); put("contentHash", "hash-$id"); put("chunkIndex", 0); put("totalChunks", 1)
        })

    private fun SQLiteDatabase.savedEntry(id: String, item: String, folder: String, note: String?) =
        insertOrThrow("stash_items", null, ContentValues().apply {
            put("id", id); put("vaultItemId", item); put("vaultType", "SAVED"); put("category", folder)
            put("savedAt", 7L); put("sourceApp", "gpay"); put("isFavorite", 1); put("createdAt", 7L); put("userNote", note)
        })

    private fun aVaultWithSavedThings() {
        val db = aVersion16Vault()
        db.item("receipt", "Paid to Ravi 450", "screenshot")
        db.item("link", "", "link")
        db.item("beach", "", "photo")
        db.savedEntry("entry-receipt", "receipt", "Bills", "keep this")
        db.savedEntry("entry-link", "link", "", null)
        db.insertOrThrow("photo_vectors", null, ContentValues().apply {
            put("itemId", "beach"); put("model", "m1"); put("pHash", 11L); put("vector", byteArrayOf(1, -2, 3))
            put("scale", 0.01f); put("madeAt", 5L)
        })
        db.close()
    }

    private fun open(vararg migrations: androidx.room.migration.Migration): VaultDatabase =
        Room.databaseBuilder(context, VaultDatabase::class.java, name)
            .addMigrations(*migrations)
            .allowMainThreadQueries()
            .build()
            .also { opened = it; it.openHelper.writableDatabase }

    private fun VaultDatabase.tablesAndIndicesNamedLike(pattern: String): List<String> =
        openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name LIKE '$pattern'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test
    fun theSavedTableAndItsIndicesAreGone() {
        aVaultWithSavedThings()
        val db = open(VaultDatabase.MIGRATION_16_17, VaultDatabase.MIGRATION_17_18)
        assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%stash%"))
    }

    @Test
    fun everythingThatWasSavedIsStillInTheVault() = runBlocking {
        aVaultWithSavedThings()
        val db = open(VaultDatabase.MIGRATION_16_17, VaultDatabase.MIGRATION_17_18)

        val items = db.vaultDao().getAll().associateBy { it.id }
        assertEquals(setOf("receipt", "link", "beach"), items.keys)
        assertEquals("Paid to Ravi 450", items.getValue("receipt").ocrText)
        assertEquals(ItemType.LINK, items.getValue("link").itemType)
        assertEquals("/files/shared_imports/receipt", items.getValue("receipt").uri)
    }

    @Test
    fun whatAPictureLooksLikeIsUntouched() = runBlocking {
        aVaultWithSavedThings()
        val db = open(VaultDatabase.MIGRATION_16_17, VaultDatabase.MIGRATION_17_18)
        assertEquals(
            listOf(PhotoVector("beach", "m1", 11L, byteArrayOf(1, -2, 3), 0.01f, madeAt = 5L)),
            db.photoVectorDao().allOf("m1"),
        )
    }

    @Test
    fun writingAnItemAgainAndRemovingItWorkWithNothingHangingOffIt() = runBlocking {
        // Before: writing a row again took its Saved entry with it, through the foreign key.
        aVaultWithSavedThings()
        val db = open(VaultDatabase.MIGRATION_16_17, VaultDatabase.MIGRATION_17_18)
        val receipt = db.vaultDao().getAll().single { it.id == "receipt" }

        db.vaultDao().insert(receipt.copy(ocrText = "Paid to Ravi 450 today"))
        assertEquals("Paid to Ravi 450 today", db.vaultDao().getAll().single { it.id == "receipt" }.ocrText)

        db.vaultDao().deleteById("receipt")
        assertFalse(db.vaultDao().getAll().any { it.id == "receipt" })
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test
    fun aNewVaultNeverHasTheTable() {
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        try {
            assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%stash%"))
        } finally {
            db.close()
        }
    }

    @Test
    fun withoutTheStepThisBuildCannotOpenAVersion16Vault() {
        aVaultWithSavedThings()
        try {
            open()
            fail("a version-16 vault was opened with no step to version 17")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), "from 16 to" in e.message.orEmpty())
        }
    }

    @Test
    fun aVersion13VaultGoesAllTheWay() {
        V13Vault.create(context, name).close()
        val db = open(*VaultDatabase.migrations())
        assertEquals(18, db.openHelper.readableDatabase.version)
        assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%stash%"))
    }
}
