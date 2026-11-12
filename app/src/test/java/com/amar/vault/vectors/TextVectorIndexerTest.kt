package com.amar.vault.vectors

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import com.amar.vault.indexing.IndexPersister
import com.amar.vault.indexing.VaultRemoval
import com.amar.vault.photos.PhotoIndex
import com.amar.vault.photos.PhotoVectors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class TextVectorIndexerTest {

    private lateinit var db: VaultDatabase
    private lateinit var space: FakeSpace

    class FakeSpace : PassageSpace {
        val index = PhotoIndex(DIM)
        val hashes = HashMap<String, String>()
        var embedded = 0
        override val textModelId = "fake-text-1"
        override suspend fun textIndex(): PhotoIndex = index
        override suspend fun embedPassage(title: String?, text: String, maxTokens: Int): FloatArray {
            embedded++
            val d = MessageDigest.getInstance("SHA-256").digest(("$title|$text").toByteArray())
            return PhotoVectors.normalized(FloatArray(DIM) { d[it % d.size] / 128f + (it % 7) / 100f })
        }
        override fun putText(rowId: String, bytes: ByteArray, scale: Float, hash: String) {
            index.put(rowId, bytes, scale); hashes[rowId] = hash
        }
        override fun forgetRows(rowIds: Collection<String>) {
            rowIds.forEach { index.remove(it); hashes.remove(it) }
        }
        override fun setTextEligible(count: Int) {}
    }

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
        space = FakeSpace()
    }

    @After
    fun close() = db.close()

    private suspend fun document(id: String, pages: Int, piecesPerPage: Int = 2, word: String = "invoice") {
        val doc = VaultDocument(id, "content://docs/$id", "$id.pdf", ItemType.PDF, "hash-$id", pages, 0, 1L)
        var n = 0
        val rows = (1..pages).flatMap { page ->
            (0 until piecesPerPage).map { k ->
                VaultItem(id = "${id}_chunk$n", uri = doc.uri, ocrText = "page $page part $k says $word total Rs 1,250.00",
                    lang = "en", itemType = ItemType.PDF, pageNum = page, sourceFile = doc.name, timestamp = 10L + n,
                    contentHash = doc.contentHash, parentDocumentId = id, chunkIndex = n++)
            }
        }
        IndexPersister(db).persistDocument(doc, rows)
    }

    private suspend fun picture(id: String, text: String) {
        IndexPersister(db).persistImageShell(VaultItem(id = id, uri = "content://media/$id", ocrText = text, lang = "en",
            itemType = ItemType.SCREENSHOT, sourceFile = "Screenshot_$id.png", timestamp = 99L))
    }

    private suspend fun stored(): List<TextVector> = db.textVectorDao().allOf(space.textModelId)

    @Test
    fun everyRowGetsAVectorWithTheHashOfItsText() = runBlocking {
        document("d1", pages = 3)
        picture("s1", "Payment successful to Ramesh Kirana Store")
        val pass = TextVectorIndexer(db, space).run(tier = 2)
        assertEquals(0, pass.remaining)
        val vectors = stored()
        assertEquals(7, vectors.size)
        val rows = db.textVectorDao().rows(vectors.map { it.rowId }).associateBy { it.id }
        vectors.forEach { assertEquals(TextPassages.hash(rows.getValue(it.rowId)), it.textHash) }
        assertEquals(7, space.index.size)
        assertEquals(768, vectors.first().vector.size)
    }

    @Test
    fun tierOneIsTheFirstPieceOfEachPageAndEveryPicture() = runBlocking {
        document("d1", pages = 3, piecesPerPage = 3)
        picture("s1", "Payment successful to Ramesh Kirana Store")
        val pass = TextVectorIndexer(db, space).run(tier = 1)
        assertEquals(0, pass.remaining)
        assertEquals(setOf("d1_chunk0", "d1_chunk3", "d1_chunk6", "s1"), stored().map { it.rowId }.toSet())
        assertEquals(6, db.textVectorDao().countWithout(space.textModelId, 2, TextPassages.MIN_CHARS))
        val coverage = db.coverageDao().get("d1")!!
        assertEquals(9, coverage.rowsTotal)
        assertEquals(3, coverage.rowsWithVector)
        assertEquals(1, coverage.tier)
    }

    @Test
    fun aRunCutShortCarriesOnWithNothingMissingAndNothingTwice() = runBlocking {
        document("d1", pages = 4)
        var stopAfter = 3
        val first = TextVectorIndexer(db, space).run(tier = 2, shouldStop = { space.embedded >= stopAfter })
        assertTrue(first.remaining > 0)
        stopAfter = Int.MAX_VALUE
        val second = TextVectorIndexer(db, space).run(tier = 2)
        assertEquals(0, second.remaining)
        val vectors = stored()
        assertEquals(8, vectors.size)
        assertEquals(8, vectors.map { it.rowId }.toSet().size)
        assertEquals("tier 2 reached", 2, db.coverageDao().get("d1")!!.tier)
    }

    @Test
    fun readingAgainReplacesTheVector() = runBlocking {
        picture("s1", "Payment successful to Ramesh Kirana Store")
        TextVectorIndexer(db, space).run(tier = 2)
        val before = stored().single()
        picture("s1", "Order placed: running shoes size 9, arriving Friday")
        assertTrue("the old vector goes with the old text", stored().isEmpty())
        TextVectorIndexer(db, space).run(tier = 2)
        val after = stored().single()
        assertFalse(before.textHash == after.textHash)
        assertEquals(TextPassages.hash(db.textVectorDao().rows(listOf("s1")).single()), after.textHash)
    }

    @Test
    fun textChangedBehindTheIndexersBackIsCaughtAsStale() = runBlocking {
        picture("s1", "Payment successful to Ramesh Kirana Store")
        TextVectorIndexer(db, space).run(tier = 2)
        db.openHelper.writableDatabase.execSQL("UPDATE vault_items SET ocrText = 'Something else entirely, a cinema booking' WHERE id = 's1'")
        val pass = TextVectorIndexer(db, space).run(tier = 2)
        assertEquals(1, pass.stale)
        assertEquals(1, pass.embedded)
        assertEquals(TextPassages.hash(db.textVectorDao().rows(listOf("s1")).single()), stored().single().textHash)
    }

    @Test
    fun removingADocumentRemovesItsVectorsFactsAndCoverage() = runBlocking {
        document("d1", pages = 2)
        document("d2", pages = 1)
        TextVectorIndexer(db, space).run(tier = 2)
        assertTrue(db.factDao().of("d1").isNotEmpty())
        VaultRemoval(db, forgetKeywords = {}, forgetVectors = { space.forgetRows(listOf(it)) }).removeDocument("d1")
        assertEquals(setOf("d2_chunk0", "d2_chunk1"), stored().map { it.rowId }.toSet())
        assertTrue(db.factDao().of("d1").isEmpty())
        assertEquals(null, db.coverageDao().get("d1"))
        assertEquals(2, space.index.size)
    }

    @Test
    fun removingAPictureRemovesItsVectorAndFacts() = runBlocking {
        picture("s1", "Paid to Ramesh Kirana Store ₹450.00 UPI transaction ID 123456789012")
        TextVectorIndexer(db, space).run(tier = 2)
        assertTrue(db.factDao().of("s1").isNotEmpty())
        VaultRemoval(db, forgetKeywords = {}).removeItem("s1")
        assertTrue(stored().isEmpty())
        assertTrue(db.factDao().of("s1").isEmpty())
    }

    @Test
    fun tooLittleTextIsNotEmbedded() = runBlocking {
        picture("s1", "ok")
        picture("s2", "Payment successful to Ramesh Kirana Store")
        TextVectorIndexer(db, space).run(tier = 2)
        assertEquals(listOf("s2"), stored().map { it.rowId })
    }

    private companion object {
        const val DIM = 768
    }
}
