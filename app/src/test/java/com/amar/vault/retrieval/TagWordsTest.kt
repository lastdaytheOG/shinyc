package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A row's tags are words like any others to the typo help, whichever place they stand in.
 *
 * While the tags were stored as a bracketed last line of the text, the first tag was read as
 * "[word" and the last as "code]". A misspelling that is one letter from the word was then two
 * from what was compared with it, and a short tag could not be reached by one at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class TagWordsTest {

    private val message = VaultItem(
        id = "sms", uri = "content://media/1", ocrText = "4471 is the number you asked for", lang = "en",
        itemType = ItemType.SCREENSHOT, timestamp = 0L, tags = "otp verification code",
    )

    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }
        override suspend fun allItemsSnapshot() = all
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    private object NoEngine : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = emptyList<String>()
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private object NoSemantic : SemanticRetriever {
        override val isReady = false
        override suspend fun embedQuery(query: String, trueLength: Boolean) = floatArrayOf()
        override suspend fun embedText(text: String) = floatArrayOf()
        override fun searchVectors(vector: FloatArray, k: Int) = emptyList<String>()
    }

    private val notes = VaultItem(
        id = "notes_chunk0", uri = "content://docs/notes", ocrText = "minutes of the first meeting", lang = "en",
        itemType = ItemType.WORD, sourceFile = "notes.docx", timestamp = 0L, tags = "word document",
        parentDocumentId = "notes",
    )

    private fun found(typed: String) = runBlocking {
        HybridSearchService(Repo(listOf(message, notes)), NoEngine, NoSemantic, BoostConfig())
            .retrieve(RetrievalRequest.forResultList(typed))
    }

    @Test
    fun aMisspellingOfTheLastTagFindsTheRow() {
        val result = found("cose")
        assertEquals(listOf("sms"), result.items.map { it.id })
        assertTrue("and the list is known to be near spellings only", result.similarSpellingsOnly)
    }

    @Test
    fun aMisspellingOfTheFirstTagFindsTheRow() {
        assertEquals(listOf("notes_chunk0"), found("wore").items.map { it.id })
    }

    @Test
    fun gluedToABracketTheTagWasOutOfReach() {
        // The control: what the typo help was given to compare with, before and now.
        val typo = FuzzyMatcher(listOf("cose"), roots = true)
        assertFalse(typo.matches("otp verification code]"))
        assertTrue(typo.matches("otp verification code"))
        assertFalse(FuzzyMatcher(listOf("wore"), roots = true).matches("[word document"))
        assertTrue(FuzzyMatcher(listOf("wore"), roots = true).matches("word document"))
    }

    @Test
    fun theTagItselfStillFindsIt() {
        val result = found("verification")
        assertEquals(listOf("sms"), result.items.map { it.id })
        assertFalse(result.similarSpellingsOnly)
    }
}
