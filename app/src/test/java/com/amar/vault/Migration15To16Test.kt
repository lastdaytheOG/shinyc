package com.amar.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.photos.PhotoVector
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
 * Opening a version-15 vault with this build. Version 16 adds one table, what each picture looks
 * like; every row that was there is there afterwards, untouched, and the new table starts empty.
 *
 * The version-15 vault is built from the schema that build exported (`schemas/…/15.json`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Migration15To16Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "vault-15-to-16-test.db"
    private var opened: VaultDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(name)
    }

    /** An empty vault exactly as the version-15 build made it. */
    private fun aVersion15Vault(): SQLiteDatabase {
        val schema = JSONObject(File("schemas/com.amar.vault.VaultDatabase/15.json").readText()).getJSONObject("database")
        assertEquals(15, schema.getInt("version"))
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
        db.version = 15
        return db
    }

    private fun SQLiteDatabase.item(id: String, text: String, type: String, pHash: Long = 0L, at: Long = 100L) =
        insertOrThrow("vault_items", null, ContentValues().apply {
            put("id", id); put("uri", "content://media/external/images/media/$id"); put("ocrText", text); put("lang", "en")
            put("itemType", type); put("pageNum", 0); put("sourceFile", id); put("timestamp", at); put("pHash", pHash)
            put("tags", ""); put("contentHash", ""); put("chunkIndex", 0); put("totalChunks", 1)
        })

    private fun aVaultWithPictures() {
        val db = aVersion15Vault()
        db.item("beach", "", "photo", pHash = 11L, at = 300L)
        db.item("receipt", "Paid to Ravi 450", "screenshot", pHash = 22L, at = 200L)
        db.item("note", "remember the milk", "text")
        db.insertOrThrow("stash_items", null, ContentValues().apply {
            put("id", "stash"); put("vaultItemId", "receipt"); put("vaultType", "SAVED"); put("category", "Bills")
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

    private fun vector(item: String, model: String = "m1", pHash: Long = 11L) =
        PhotoVector(item, model, pHash, byteArrayOf(1, -2, 3), 0.01f, madeAt = 5L)

    @Test
    fun everythingStoredIsStillThereAndTheNewTableIsEmpty() = runBlocking {
        aVaultWithPictures()
        val db = open(VaultDatabase.MIGRATION_15_16)

        val items = db.vaultDao().getAll().associateBy { it.id }
        assertEquals(setOf("beach", "receipt", "note"), items.keys)
        assertEquals("Paid to Ravi 450", items.getValue("receipt").ocrText)
        assertEquals(ItemType.PHOTO, items.getValue("beach").itemType)
        val saved = db.stashItemDao().getStashItemsByType("SAVED").first().single()
        assertEquals(listOf("receipt", "Bills", "keep"), listOf(saved.vaultItemId, saved.category, saved.userNote))
        assertEquals(0, db.photoVectorDao().countOf("m1"))
    }

    @Test
    fun thePhotosStillToEmbedAreThePhotosOnly() = runBlocking {
        aVaultWithPictures()
        val db = open(VaultDatabase.MIGRATION_15_16)
        val dao = db.photoVectorDao()

        // Only the photo: the screenshot ("receipt") is found by the words on it.
        assertEquals(listOf("beach"), dao.picturesWithout("m1", 10).map { it.id })
        dao.upsert(vector("beach"))
        assertEquals(emptyList<String>(), dao.picturesWithout("m1", 10).map { it.id })
        assertEquals(0, dao.countWithout("m1"))
        // Another model has its own vectors: the photo still needs one from it.
        assertEquals(1, dao.countWithout("m2"))
        // A picture that changed under the same id (another perceptual hash) is embedded again.
        dao.upsert(vector("beach", pHash = 99L))
        assertEquals(listOf("beach"), dao.picturesWithout("m1", 10).map { it.id })
        // A screenshot embedded before only photos were is found, to be forgotten.
        dao.upsert(vector("receipt"))
        assertEquals(listOf("receipt"), dao.ofItemsNotPhotos())
    }

    @Test
    fun aVectorSurvivesItsPictureBeingWrittenAgain() = runBlocking {
        // The reason there is no cascade: the indexer writes a picture's row again (INSERT OR
        // REPLACE) once its text has been read, and that must not take its vector with it.
        aVaultWithPictures()
        val db = open(VaultDatabase.MIGRATION_15_16)
        db.photoVectorDao().upsert(vector("beach"))
        val beach = db.vaultDao().getAll().single { it.id == "beach" }
        db.vaultDao().insert(beach.copy(ocrText = "SURF SHOP"))
        assertEquals(vector("beach"), db.photoVectorDao().get("m1", "beach"))
    }

    @Test
    fun vectorsOfPicturesThatAreGoneAreRemoved() = runBlocking {
        aVaultWithPictures()
        val db = open(VaultDatabase.MIGRATION_15_16)
        val dao = db.photoVectorDao()
        dao.upsert(vector("beach"))
        dao.upsert(vector("ghost"))
        assertEquals(1, dao.deleteOrphans())
        assertEquals(listOf("beach"), dao.allOf("m1").map { it.itemId })
    }

    @Test
    fun withoutTheStepThisBuildCannotOpenAVersion15Vault() {
        aVaultWithPictures()
        try {
            open()
            fail("a version-15 vault was opened with no step to version 16")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), "from 15 to" in e.message.orEmpty())
        }
    }

    @Test
    fun aVersion13VaultGoesAllTheWay() = runBlocking {
        V13Vault.create(context, name).close()
        val db = open(*VaultDatabase.migrations())
        assertEquals(16, db.openHelper.readableDatabase.version)
        assertEquals(0, db.photoVectorDao().countOf("m1"))
    }
}
