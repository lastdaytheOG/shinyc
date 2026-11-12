package com.amar.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class Migration18To19Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "vault-18-to-19-test.db"
    private var opened: VaultDatabase? = null

    @After
    fun close() {
        opened?.close()
        context.deleteDatabase(name)
    }

    private fun aVersion18Vault(): SQLiteDatabase {
        val schema = JSONObject(File("schemas/com.amar.vault.VaultDatabase/18.json").readText()).getJSONObject("database")
        assertEquals(18, schema.getInt("version"))
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            entity.optJSONArray("contentSyncTriggers")?.let { t -> for (j in 0 until t.length()) db.execSQL(t.getString(j)) }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = 18
        return db
    }

    private fun SQLiteDatabase.item(id: String, text: String, type: String, parent: String? = null) =
        insertOrThrow("vault_items", null, ContentValues().apply {
            put("id", id); put("uri", "content://x/$id"); put("ocrText", text); put("lang", "en")
            put("itemType", type); put("pageNum", 1); put("sourceFile", "$id.pdf"); put("timestamp", 100L); put("pHash", 0L)
            put("tags", ""); put("contentHash", "hash-$id"); put("chunkIndex", 0); put("totalChunks", 1)
            if (parent != null) put("parentDocumentId", parent)
        })

    private fun SQLiteDatabase.meta(itemId: String, type: String, value: String, num: Double? = null, ts: Long? = null) =
        insertOrThrow("vault_metadata", null, ContentValues().apply {
            put("vaultItemId", itemId); put("type", type); put("value", value)
            if (num != null) put("numericValue", num)
            if (ts != null) put("timestampValue", ts)
            put("confidence", 0.8f); put("source", "rule"); put("extractionVersion", "v3")
        })

    private fun open(): VaultDatabase = Room.databaseBuilder(context, VaultDatabase::class.java, name)
        .addMigrations(VaultDatabase.MIGRATION_18_19).allowMainThreadQueries().build().also { opened = it }

    private fun tables(db: VaultDatabase): Set<String> = db.openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    @Test
    fun everythingStoredStaysAndTheNewTablesAreThere() = runBlocking {
        aVersion18Vault().apply {
            item("shot", "Paid to Ramesh Kirana Store 450", "screenshot")
            item("doc_chunk0", "Total Rs 1,250.00", "pdf", parent = "doc")
            close()
        }
        val db = open()
        assertEquals(19, db.openHelper.readableDatabase.version)
        assertEquals(setOf("shot", "doc_chunk0"), db.vaultDao().getAll().map { it.id }.toSet())
        val t = tables(db)
        for (table in listOf("text_vectors", "coverage", "facts", "aliases", "opens")) assertTrue(table, table in t)
        for (table in VaultDatabase.DROPPED_IN_19) assertFalse(table, table in t)
        assertEquals(0, db.textVectorDao().rowsWithVector("any"))
    }

    @Test
    fun metadataIsCopiedIntoFactsWithItsKind() = runBlocking {
        aVersion18Vault().apply {
            item("shot", "Paid to Ramesh Kirana Store 450", "screenshot")
            item("doc_chunk0", "Total Rs 1,250.00 PNR 2468135790", "pdf", parent = "doc")
            meta("shot", "AMOUNT", "450", num = 450.0)
            meta("shot", "PAYMENT_APP", "PhonePe")
            meta("shot", "SOURCE_TYPE", "SCREENSHOT")
            meta("doc_chunk0", "DATE", "2026-10-12", ts = 1_760_000_000_000L)
            meta("doc_chunk0", "PNR", "2468135790")
            meta("doc_chunk0", "DOCUMENT_TYPE", "TICKET")
            close()
        }
        val db = open()
        val shot = db.factDao().of("shot").associateBy { it.kind }
        assertEquals(setOf(com.amar.vault.vectors.FactKind.AMOUNT, com.amar.vault.vectors.FactKind.PARTY), shot.keys)
        assertEquals(450.0, shot.getValue("AMOUNT").valueNum!!, 0.0)
        val doc = db.factDao().of("doc")
        assertEquals(setOf("DATE", "ID_NUMBER", "DOC_KIND"), doc.map { it.kind }.toSet())
        assertTrue(doc.all { it.rowId == "doc_chunk0" && it.ruleVersion == 0 && it.spanStart == -1 })
        assertEquals(1_760_000_000_000L, doc.single { it.kind == "DATE" }.valueDate)
    }

    @Test
    fun newRowsCanBeWrittenToEveryNewTable() = runBlocking {
        aVersion18Vault().close()
        val db = open()
        db.textVectorDao().upsertAll(listOf(com.amar.vault.vectors.TextVector("r1", "m", 0, "h", ByteArray(768), 0.01f, 1L)))
        db.coverageDao().ensure("r1", 3, 1L)
        db.aliasDao().upsert(com.amar.vault.vectors.Alias("bijli", "electricity", 1f, 1L))
        db.openDao().insert(com.amar.vault.vectors.Open(queryNorm = "bijli bill", itemId = "r1", verdict = "POSSIBLE", at = 1L))
        assertEquals(1, db.textVectorDao().allOf("m").size)
        assertEquals(3, db.coverageDao().get("r1")!!.priority)
        assertEquals(1, db.aliasDao().all().size)
        assertEquals(1, db.openDao().count())
    }
}
