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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Migration17To18Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "vault-17-to-18-test.db"
    private var opened: VaultDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(name)
    }

    private fun aVersion17Vault(): SQLiteDatabase {
        val schema = JSONObject(File("schemas/com.amar.vault.VaultDatabase/17.json").readText()).getJSONObject("database")
        assertEquals(17, schema.getInt("version"))
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
        db.version = 17
        return db
    }

    private fun SQLiteDatabase.item(id: String, text: String, type: String) =
        insertOrThrow("vault_items", null, ContentValues().apply {
            put("id", id); put("uri", "/files/shared_imports/$id"); put("ocrText", text); put("lang", "en")
            put("itemType", type); put("pageNum", 0); put("sourceFile", id); put("timestamp", 100L); put("pHash", 11L)
            put("tags", ""); put("contentHash", "hash-$id"); put("chunkIndex", 0); put("totalChunks", 1)
        })

    private fun SQLiteDatabase.chatMessage(id: String, role: String, text: String) =
        insertOrThrow("chat_history", null, ContentValues().apply {
            put("messageId", id); put("sessionId", "default_session"); put("role", role); put("content", text)
            put("timestamp", 9L); put("sourcesJson", "[]")
        })

    private fun SQLiteDatabase.model(id: String) =
        insertOrThrow("local_models", null, ContentValues().apply {
            put("modelId", id); put("displayName", "Model $id"); put("fileName", "$id.gguf")
            put("downloadUrl", "https://example.invalid/$id.gguf"); put("sha256", "00"); put("sizeBytes", 1000L)
            put("requiredRamGb", 4.0f); put("status", "READY"); put("downloadProgress", 100); put("isEnabled", 1)
        })

    private fun aVaultWithAChatAndTwoModels() {
        val db = aVersion17Vault()
        db.item("receipt", "Paid to Ravi 450", "screenshot")
        db.item("beach", "", "photo")
        db.chatMessage("m1", "user", "bills from last month")
        db.chatMessage("m2", "assistant", "Found 1 result")
        db.model("small")
        db.model("large")
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
    fun theChatTableTheModelTableAndTheirIndicesAreGone() {
        aVaultWithAChatAndTwoModels()
        val db = open(VaultDatabase.MIGRATION_17_18, VaultDatabase.MIGRATION_18_19)
        assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%chat_history%"))
        assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%local_models%"))
    }

    @Test
    fun everythingInTheVaultIsStillThere() = runBlocking {
        aVaultWithAChatAndTwoModels()
        val db = open(VaultDatabase.MIGRATION_17_18, VaultDatabase.MIGRATION_18_19)

        val items = db.vaultDao().getAll().associateBy { it.id }
        assertEquals(setOf("receipt", "beach"), items.keys)
        assertEquals("Paid to Ravi 450", items.getValue("receipt").ocrText)
        assertEquals(
            listOf(PhotoVector("beach", "m1", 11L, byteArrayOf(1, -2, 3), 0.01f, madeAt = 5L)),
            db.photoVectorDao().allOf("m1"),
        )
    }

    @Test
    fun writingAnItemAgainAndRemovingItStillWork() = runBlocking {
        aVaultWithAChatAndTwoModels()
        val db = open(VaultDatabase.MIGRATION_17_18, VaultDatabase.MIGRATION_18_19)
        val receipt = db.vaultDao().getAll().single { it.id == "receipt" }

        db.vaultDao().insert(receipt.copy(ocrText = "Paid to Ravi 450 today"))
        assertEquals("Paid to Ravi 450 today", db.vaultDao().getAll().single { it.id == "receipt" }.ocrText)

        db.vaultDao().deleteById("receipt")
        assertFalse(db.vaultDao().getAll().any { it.id == "receipt" })
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test
    fun aNewVaultNeverHasTheTables() {
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
        try {
            assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%chat_history%"))
            assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%local_models%"))
        } finally {
            db.close()
        }
    }

    @Test
    fun withoutTheStepThisBuildCannotOpenAVersion17Vault() {
        aVaultWithAChatAndTwoModels()
        try {
            open()
            fail("a version-17 vault was opened with no step to version 18")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), "from 17 to" in e.message.orEmpty())
        }
    }

    @Test
    fun aVersion13VaultGoesAllTheWay() {
        V13Vault.create(context, name).close()
        val db = open(*VaultDatabase.migrations())
        assertEquals(19, db.openHelper.readableDatabase.version)
        assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%chat_history%"))
        assertEquals(emptyList<String>(), db.tablesAndIndicesNamedLike("%local_models%"))
    }
}
