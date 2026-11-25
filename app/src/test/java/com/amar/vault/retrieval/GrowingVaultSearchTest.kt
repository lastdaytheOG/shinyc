package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class GrowingVaultSearchTest {

    private fun screenshot(n: Int) = VaultItem(
        id = "shot%04d".format(n), uri = "content://shots/$n", ocrText = "invoice number $n", lang = "en",
        itemType = ItemType.SCREENSHOT, sourceFile = "$n.png", timestamp = n.toLong(),
    )

    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
        override suspend fun inSnapshotOrder(ids: List<String>) = getByIds(ids).sortedByDescending { it.timestamp }
        override suspend fun allItemsSnapshot() = all
        override suspend fun dateRangeItemIds(startMs: Long, endMs: Long) = emptyList<String>()
        override suspend fun itemIdsHavingDate() = emptySet<String>()
        override suspend fun amountGreaterThanIds(value: Double) = emptyList<String>()
        override suspend fun itemIdsByTypeValue(type: String, value: String) = emptyList<String>()
        override suspend fun effectiveDates(items: List<VaultItem>) = items.associate { it.id to it.timestamp }
    }

    private class Lexical(private val all: List<VaultItem>) : LexicalRetriever {
        override fun bm25(query: String, limit: Int) =
            all.filter { query in it.ocrText }.sortedByDescending { it.timestamp }.map { it.id }.take(minOf(limit, 300))
        override suspend fun fts(query: String, limit: Int) = bm25(query, limit)
    }

    private fun found(count: Int): Set<String> = runBlocking {
        val vault = (1..count).map(::screenshot)
        HybridSearchService(Repo(vault), Lexical(vault), BoostConfig())
            .retrieve(RetrievalRequest.forResultList("invoice")).items.map { it.id }.toSet()
    }

    @Test
    fun everythingFoundInASmallVaultIsStillFoundAsItGrows() {
        val early = found(40)
        assertEquals(40, early.size)
        for (count in listOf(80, 160, 280)) {
            assertEquals("of the first 40, lost at $count", emptySet<String>(), early - found(count))
        }
    }
}
