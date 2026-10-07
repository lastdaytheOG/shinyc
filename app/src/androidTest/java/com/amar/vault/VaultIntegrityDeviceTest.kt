package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What must be true of the vault on this device, whatever is in it: checked against the real
 * database, after an upgrade from an older version and after anything new has been indexed.
 * It reads only; it changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class VaultIntegrityDeviceTest {

    private val db by lazy {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java).database()
    }

    private fun <T> rows(sql: String, read: (android.database.Cursor) -> T): List<T> =
        db.openHelper.readableDatabase.query(sql).use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    @Test
    fun everyTypeIsOneOfTheList() {
        val stored = rows("SELECT itemType, COUNT(*) FROM vault_items GROUP BY itemType") { it.getString(0) to it.getInt(1) }
        val strays = stored.filter { ItemType.ofStored(it.first) == null }
        assertTrue("types that are not on the list: $strays", strays.isEmpty())
        val documentTypes = rows("SELECT DISTINCT itemType FROM documents") { it.getString(0) }
        assertTrue("$documentTypes", documentTypes.all { ItemType.ofStored(it)?.isDocument == true })
    }

    @Test
    fun noTextEndsWithALineOfTags() = runBlocking {
        val glued = db.vaultDao().getAll().filter { FormerStoredText.split(it.ocrText).tags.isNotEmpty() }.map { it.id }
        assertTrue("rows whose text still carries its tags: ${glued.take(5)} (${glued.size})", glued.isEmpty())
    }

    @Test
    fun everyItemIsTaggedAsTheRulesWouldTagItNow() = runBlocking {
        // Whether it was tagged when it was indexed or tagged again since, by rules that are
        // the current ones: nothing on the device is waiting to be tagged differently.
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals(0, com.amar.vault.indexing.AutoTagUpkeep(db, app).countOutOfDate())
    }

    @Test
    fun everyPieceHasItsDocumentAndEveryDocumentItsCount() = runBlocking {
        assertEquals("pieces that name a document there is no record of", 0, db.vaultDocumentDao().countPiecesWithoutDocument())
        val counted = rows("SELECT parentDocumentId, COUNT(*) FROM vault_items WHERE parentDocumentId IS NOT NULL GROUP BY parentDocumentId") {
            it.getString(0) to it.getInt(1)
        }.toMap()
        val recorded = db.vaultDocumentDao().getAll()
        val wrong = recorded.filter { it.chunkCount != (counted[it.id] ?: 0) }.map { "${it.name}: says ${it.chunkCount}, has ${counted[it.id] ?: 0}" }
        assertTrue("documents whose count is off: $wrong", wrong.isEmpty())
        // A piece carries its document's type, name and address.
        val drifted = rows(
            "SELECT COUNT(*) FROM vault_items v JOIN documents d ON d.id = v.parentDocumentId " +
                "WHERE v.itemType <> d.itemType OR v.sourceFile <> d.name OR v.uri <> d.uri OR v.contentHash <> d.contentHash"
        ) { it.getInt(0) }.single()
        assertEquals("pieces that disagree with their document's record", 0, drifted)
    }

    @Test
    fun theFullTextIndexHoldsTheTextThatIsStored() = runBlocking {
        // For a spread of rows: a plain word of the row finds the row, and the row's first
        // tag does not unless the page says it too.
        val all = db.vaultDao().getAll().filter { it.ocrText.isNotBlank() }
        val sample = all.filterIndexed { i, _ -> i % (all.size / 40).coerceAtLeast(1) == 0 }
        var checked = 0
        for (row in sample) {
            val word = PLAIN_WORD.findAll(row.ocrText.lowercase()).map { it.value }.maxByOrNull { it.length } ?: continue
            val found = rows("SELECT v.id FROM vault_items v JOIN vault_fts f ON v.rowid = f.rowid WHERE vault_fts MATCH '$word'") { it.getString(0) }
            assertTrue("\"$word\" is on ${row.id} and the index does not find it there", row.id in found)
            checked++
        }
        assertTrue("nothing to check on this device", checked > 0 || all.isEmpty())

        val tagOnly = all.firstOrNull { row ->
            val tag = row.tags.split(' ').firstOrNull { it.length >= 4 } ?: return@firstOrNull false
            !row.ocrText.contains(tag, ignoreCase = true)
        } ?: return@runBlocking
        val tag = tagOnly.tags.split(' ').first { it.length >= 4 && !tagOnly.ocrText.contains(it, ignoreCase = true) }
        val byTag = db.vaultDao().searchFts(tag).first().map { it.id }
        assertTrue("\"$tag\" is only a tag of ${tagOnly.id}, yet the text index has it there", tagOnly.id !in byTag)
    }

    private companion object {
        /**
         * A word as the full-text index cuts it: that tokenizer takes every character outside
         * ASCII for part of a word, so `securities”` is one word to it and is not "securities".
         */
        val PLAIN_WORD = Regex("(?<![A-Za-z0-9]|[^\\x00-\\x7F])[a-z]{6,}(?![A-Za-z0-9]|[^\\x00-\\x7F])")
    }
}
