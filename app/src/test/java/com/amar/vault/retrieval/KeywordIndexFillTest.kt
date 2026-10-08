package com.amar.vault.retrieval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Filling the keyword engine from the database when the app starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class KeywordIndexFillTest {

    private lateinit var db: VaultDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    /** Stands in for the engine: keeps what it was given, in the order given. */
    private open class Recording : Bm25Index {
        val added = ArrayList<Pair<String, String>>()
        override fun addDocument(docId: String, text: String, more: String) { added.add(docId to "$text $more") }
        override fun removeDocument(docId: String) = false
        override fun search(query: String, limit: Int) = emptyList<String>()
        override fun clear() = added.clear()
    }

    private fun page(n: Int) = VaultItem(
        id = "act_chunk$n", uri = "content://docs/act", ocrText = "page $n of the act", lang = "en",
        itemType = ItemType.PDF, pageNum = n + 1, sourceFile = "Finance_Act-2026.pdf", timestamp = n.toLong(),
        tags = "pdf document", parentDocumentId = "act", chunkIndex = n,
    )

    private val picture = VaultItem(
        id = "shot", uri = "content://media/1", ocrText = "paid rs 250", lang = "en",
        itemType = ItemType.SCREENSHOT, sourceFile = "2336", timestamp = 0L, tags = "receipt payment",
    )

    private val link = VaultItem(
        id = "link", uri = "https://example.com", ocrText = "", lang = "en", itemType = ItemType.LINK,
        sourceFile = "example.com", timestamp = 0L, title = "An Example: Page",
    )

    @Test
    fun everyRowIsGivenToTheEngineInTheOrderStored() = runBlocking {
        // More rows than are read at a time, so the fill goes round more than once.
        val pages = (0 until 1203).map(::page)
        db.vaultDao().insertAll(pages.subList(0, 700))
        db.vaultDao().insert(picture)
        db.vaultDao().insertAll(pages.subList(700, 1203))
        db.vaultDao().insert(link)
        val stored = pages.subList(0, 700) + picture + pages.subList(700, 1203) + link

        val engine = Recording()
        val report = KeywordIndexFill(db, engine).fill()

        assertEquals(stored.map { it.id }, engine.added.map { it.first })
        assertEquals(stored.map { it.id }, report.ids)
        assertEquals(1205, report.items)
    }

    @Test
    fun aRowIsGivenAsItIsWhenItIsIndexed() = runBlocking {
        // The fill works a document's name out once for all its pages; the text must still be
        // what indexing one row gives the engine ([KeywordText.of]).
        val rows = listOf(page(0), page(1), picture, link, page(2).copy(title = "Its own title"))
        db.vaultDao().insertAll(rows)

        val engine = Recording()
        KeywordIndexFill(db, engine).fill()

        assertEquals(rows.map { it.id to KeywordText.of(it) }, engine.added)
    }

    @Test
    fun aSearchWaitsForAFillThatIsRunning() = runBlocking {
        db.vaultDao().insertAll(listOf(page(0), page(1)))
        val hold = CountDownLatch(1)
        val engine = object : Recording() {
            override fun addDocument(docId: String, text: String, more: String) {
                hold.await(10, TimeUnit.SECONDS)
                super.addDocument(docId, text, more)
            }
        }
        val fill = KeywordIndexFill(db, engine)
        withTimeout(1_000) { fill.awaitFilled() }   // none started: nothing to wait for

        val running = fill.start(scope)
        assertNull("still filling", withTimeoutOrNull(300) { fill.awaitFilled() })
        hold.countDown()
        withTimeout(10_000) { fill.awaitFilled() }
        assertEquals(2, engine.added.size)
        assertEquals(2, running.await()!!.items)
        assertNotNull(fill.lastReport)
    }

    @Test
    fun aFillThatFailsDoesNotHoldSearchUp() = runBlocking {
        db.vaultDao().insert(page(0))
        val engine = object : Recording() {
            override fun addDocument(docId: String, text: String, more: String) = throw IllegalStateException("no engine")
        }
        val fill = KeywordIndexFill(db, engine)
        val running = fill.start(scope)
        withTimeout(10_000) { fill.awaitFilled() }
        assertNull(running.await())
        assertTrue(engine.added.isEmpty())
    }
}
