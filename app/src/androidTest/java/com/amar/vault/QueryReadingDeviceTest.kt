package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.retrieval.RetrievalRequest
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How a query is read, against the real database and the real search service: the vault is
 * asked whether it says a phrase, and a period that holds nothing does not empty the list.
 *
 * One page is stored for the test, with words no other page has, and removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
class QueryReadingDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val services = EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java)
    private val db = services.database()
    private val bm25: com.amar.vault.retrieval.Bm25Index = EntryPointAccessors.fromApplication(
        app, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
    ).bm25Index()

    /** Stored years ago: it is from no "last month" and no "today". */
    private val page = VaultItem(
        id = "query-reading-test", uri = "content://test/query-reading", lang = "en", itemType = ItemType.TEXT,
        ocrText = "Minutes of the zyxwvian society. The last quorumday of term was moved to a Friday.",
        timestamp = 946_684_800_000L,
    )

    private fun store() = runBlocking {
        db.vaultDao().insert(page)
        bm25.addDocument(page.id, com.amar.vault.retrieval.KeywordText.of(page))
    }

    @After
    fun remove(): Unit = runBlocking {
        db.vaultDao().deleteById(page.id)
        bm25.removeDocument(page.id)
        Unit
    }

    @Test
    fun theVaultIsAskedWhetherItSaysAPhrase() = runBlocking {
        store()
        val says: suspend (String) -> Boolean = { phrase -> db.vaultDao().anyTextMatches("\"$phrase*\"") }

        assertTrue(says("last quorumday"))
        assertTrue("the second word may be longer on the page", says("last quorum"))
        assertFalse("the words are both there, but not next to each other", says("last friday"))
        assertFalse(says("last zzzzunknown"))

        // So in a question, this "last" is a word of what is asked about…
        assertEquals(setOf("ORDER:last"), OrderWordsInPhrases.asWords("when is the last quorumday", says))
        // …and this one is an order.
        assertEquals(emptySet<String>(), OrderWordsInPhrases.asWords("my last zzzzunknown bill", says))
    }

    @Test
    fun aPeriodThatHoldsNothingDoesNotEmptyTheList() = runBlocking {
        store()
        val result = services.retrievalService().retrieve(RetrievalRequest.forResultList("zyxwvian parso"))

        assertTrue("the page is listed for its word: ${result.items.map { it.id }}", result.items.any { it.id == page.id })
        assertTrue("and the caller is told that \"parso\" was dropped as a date", result.filterDropped)
    }

    @Test
    fun wordsInQuotesAreSearchedAsWords() = runBlocking {
        store()
        val request = RetrievalRequest.forResultList("zyxwvian \"last\"")
        assertTrue("nothing is read into it", request.plan!!.understood.isEmpty())
        assertTrue(services.retrievalService().retrieve(request).items.any { it.id == page.id })
    }
}
