package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class MeaningLaneTest {

    private fun row(id: String, text: String, doc: String? = null, type: ItemType = if (doc != null) ItemType.PDF else ItemType.SCREENSHOT) =
        VaultItem(id = id, uri = "content://x/$id", ocrText = text, lang = "en", itemType = type,
            sourceFile = "${doc ?: id}.pdf", timestamp = 1L, parentDocumentId = doc, chunkIndex = id.last().digitToIntOrNull() ?: 0)

    private val vault = listOf(
        row("lease_chunk0", "The landlord returns the security amount within thirty days after the tenant vacates.", doc = "lease"),
        row("lease_chunk1", "Either party may end the agreement with one month's notice.", doc = "lease"),
        row("note", "deposit slip for the gas cylinder"),
        row("gpu_chunk0", "Graphics cards run thousands of small threads at once.", doc = "gpu"),
    )

    private class Repo(private val all: List<VaultItem>) : SearchRepository {
        override suspend fun getByIds(ids: List<String>) = all.filter { it.id in ids }.sortedBy { it.id }
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

    private class Meaning(
        private val hits: List<Pair<String, Float>>,
        private val stale: Set<String> = emptySet(),
        private val fail: Boolean = false,
        private val slow: Boolean = false,
        private val scanned: Int = 1000,
        private val margin: Float = 0.2f,
    ) : MeaningRetriever {
        var calls = 0
        override val isReady = true
        override suspend fun scan(query: String, k: Int, only: ((String) -> Boolean)?): MeaningScan {
            calls++
            if (fail) error("model failed")
            if (slow) kotlinx.coroutines.delay(10_000)
            val kept = hits.filter { only == null || only(it.first) }
            return MeaningScan(kept.map { MeaningHit(it.first, it.second, margin) }, mean = 0.05f, deviation = 0.05f, scanned = scanned, coverage = 1f)
        }
        override fun isCurrent(row: VaultItem) = row.id !in stale
    }

    private fun service(meaning: MeaningRetriever) = HybridSearchService(Repo(vault), NoEngine, BoostConfig(), meaning = meaning)

    private val strong = listOf("lease_chunk0" to 0.62f, "lease_chunk1" to 0.55f, "gpu_chunk0" to 0.10f)

    @Test
    fun anItemWithNoTypedWordIsFoundByMeaningInItsOwnGroup() = runBlocking {
        val result = service(Meaning(strong)).retrieve(RetrievalRequest("rent refund timeline"))
        assertTrue(result.items.isEmpty())
        assertEquals(listOf("lease"), result.meaning!!.shown.map { it.item.parentDocumentId })
        assertEquals(EvidenceKind.MEANING, result.groups.single().kind)
        assertEquals(listOf("lease"), result.groups.single().ids)
        assertEquals(Verdict.POSSIBLE, result.verdict)
    }

    @Test
    fun theTypedWordListIsTheSameWithTheMeaningLaneOnAndOff() = runBlocking {
        val on = service(Meaning(strong + ("note" to 0.6f))).retrieve(RetrievalRequest("deposit slip"))
        val off = service(Meaning(strong)).retrieve(RetrievalRequest("deposit slip", tuning = RetrievalTuning(semanticEnabled = false)))
        assertEquals(off.items.map { it.id }, on.items.map { it.id })
        assertEquals(listOf("note"), on.items.map { it.id })
        assertNull(off.meaning)
        assertEquals("a row that says every word comes first", EvidenceKind.SAYS, on.groups.first().kind)
        assertFalse("one card per item: the note is not shown twice", on.groups.drop(1).any { "note" in it.ids })
    }

    @Test
    fun aStaleVectorIsNotShown() = runBlocking {
        val result = service(Meaning(strong, stale = setOf("lease_chunk0", "lease_chunk1"))).retrieve(RetrievalRequest("rent refund"))
        assertTrue(result.meaning!!.closest.none { it.item.parentDocumentId == "lease" })
    }

    @Test
    fun theKindFilterNarrowsTheMeaningLaneToo() = runBlocking {
        val onlyPictures: (VaultItem) -> Boolean = { it.itemType.isImage }
        val result = service(Meaning(strong + ("note" to 0.6f))).retrieve(RetrievalRequest("rent refund", only = onlyPictures))
        assertTrue(result.meaning!!.closest.all { it.item.itemType.isImage })
    }

    @Test
    fun aFailingMeaningLaneLeavesTheKeywordResults() = runBlocking {
        val result = service(Meaning(strong, fail = true)).retrieve(RetrievalRequest("deposit slip"))
        assertEquals(listOf("note"), result.items.map { it.id })
        assertNull(result.meaning)
    }

    @Test
    fun aMeaningLanePastItsDeadlineSaysSo() = runBlocking {
        val result = service(Meaning(strong, slow = true)).retrieve(RetrievalRequest("deposit slip"))
        assertEquals(listOf("note"), result.items.map { it.id })
        assertTrue(result.meaning!!.timedOut)
    }

    @Test
    fun aWeakSpreadShowsNothingByMeaning() = runBlocking {
        val flat = listOf("lease_chunk0" to 0.07f, "gpu_chunk0" to 0.06f)
        val result = service(Meaning(flat)).retrieve(RetrievalRequest("passport renewal form"))
        assertTrue(result.meaning!!.shown.isEmpty())
        assertTrue(result.groups.none { it.kind == EvidenceKind.MEANING })
    }

    @Test
    fun aSmallVaultIsJudgedByTheAnchorMarginAlone() = runBlocking {
        val few = listOf("lease_chunk0" to 0.30f, "gpu_chunk0" to 0.12f)
        val shown = service(Meaning(few, scanned = 5)).retrieve(RetrievalRequest("rent refund")).meaning!!.shown
        assertEquals(listOf("lease_chunk0"), shown.map { it.item.id })
        val belowAnchors = service(Meaning(few, scanned = 5, margin = -0.2f)).retrieve(RetrievalRequest("rent refund")).meaning!!.shown
        assertTrue(belowAnchors.isEmpty())
        val noLead = listOf("lease_chunk0" to 0.30f, "gpu_chunk0" to 0.29f)
        assertTrue(service(Meaning(noLead, scanned = 5, margin = 0.01f)).retrieve(RetrievalRequest("rent refund")).meaning!!.shown.isEmpty())
        assertEquals("a clear anchor margin is enough without a lead", 2,
            service(Meaning(noLead, scanned = 5, margin = 0.2f)).retrieve(RetrievalRequest("rent refund")).meaning!!.shown.size)
    }
}
