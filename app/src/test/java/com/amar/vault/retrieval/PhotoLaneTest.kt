package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import com.amar.vault.photos.PhotoMatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Photos found by what is in them come back as their own list ([RetrievalResult.photos]), beside
 * the rows that say the query's words, which are found and ordered exactly as they are without
 * photo search. A period or a chip narrows the photos as it narrows the list; a failed photo model
 * lists no photos and leaves the words working.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PhotoLaneTest {

    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun photo(id: String, at: Long = now, text: String = "") = VaultItem(
        id = id, uri = "content://media/external/images/media/$id", ocrText = text, lang = "und",
        itemType = ItemType.PHOTO, sourceFile = id, timestamp = at,
    )

    private val vault = listOf(
        photo("beach"), photo("cat"), photo("old-beach", at = now - 400 * day),
        photo("receipt", text = "payment of rs 500 paid to ravi").copy(itemType = ItemType.SCREENSHOT),
        VaultItem(id = "bill_chunk0", uri = "content://docs/bill", ocrText = "electricity charges for the beach house",
            lang = "en", itemType = ItemType.PDF, pageNum = 1, sourceFile = "bill.pdf", timestamp = now,
            parentDocumentId = "bill"),
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

    private object NoKeywordIndex : LexicalRetriever {
        override fun bm25(query: String, limit: Int) = emptyList<String>()
        override suspend fun fts(query: String, limit: Int) = emptyList<String>()
    }

    private object NoSemantic : SemanticRetriever {
        override val isReady = false
        override suspend fun embedQuery(query: String, trueLength: Boolean) = floatArrayOf()
        override suspend fun embedText(text: String) = floatArrayOf()
        override fun searchVectors(vector: FloatArray, k: Int) = emptyList<String>()
    }

    /** Ranks every picture for any query: beach, old beach, then cat; counts its calls. */
    private class Photos(private val fail: Boolean = false) : PhotoRetriever {
        var calls = 0
        override suspend fun search(query: String, k: Int, only: ((String) -> Boolean)?): List<PhotoMatch> {
            calls++
            if (fail) error("the photo model fell over")
            return listOf(PhotoMatch("beach", 0.31f), PhotoMatch("old-beach", 0.30f), PhotoMatch("cat", 0.12f))
                .filter { only == null || only(it.itemId) }.take(k)
        }
    }

    private fun service(photos: PhotoRetriever) =
        HybridSearchService(Repo(vault), NoKeywordIndex, NoSemantic, BoostConfig(), photos)

    private fun search(text: String, photos: PhotoRetriever = Photos(), only: ((VaultItem) -> Boolean)? = null,
                       lane: Boolean = true): RetrievalResult = runBlocking {
        val request = RetrievalRequest.forResultList(text, only)
        service(photos).retrieve(request.copy(tuning = request.tuning.copy(photoLane = lane)))
    }

    @Test
    fun aPictureWithNoTextIsFoundByWhatIsInIt() {
        val result = search("dog running on the sand")
        assertEquals(listOf("beach", "old-beach", "cat"), result.photos.map { it.item.id })
    }

    @Test
    fun theRowsThatSayTheWordsAreTheSameWithOrWithoutPhotos() {
        // "dog running on the sand" lists the bill for saying "the": the word search's own doing.
        for (q in listOf("paid to ravi", "electricity", "beach", "rs 500", "dog running on the sand")) {
            val with = search(q).items.map { it.id }
            val without = search(q, PhotoRetriever.NONE).items.map { it.id }
            assertEquals("\"$q\"", without, with)
        }
    }

    @Test
    fun aPeriodNarrowsThePhotosToIt() {
        val ids = search("beach today").photos.map { it.item.id }
        assertTrue("today's beach photo: $ids", "beach" in ids)
        assertTrue("the beach photo from 400 days ago is not from today: $ids", "old-beach" !in ids)
    }

    @Test
    fun aChipThatShowsOnlyDocumentsShowsNoPhotos() {
        assertEquals(emptyList<PhotoHit>(), search("beach", only = { it.itemType.isDocument }).photos)
    }

    @Test
    fun aFailedPhotoModelListsNoPhotosAndTheWordsStillWork() {
        val result = search("paid to ravi", Photos(fail = true))
        assertEquals(emptyList<PhotoHit>(), result.photos)
        assertEquals(listOf("receipt"), result.items.map { it.id })
    }

    @Test
    fun withTheLaneOffNoPhotoModelRuns() {
        val photos = Photos()
        assertEquals(emptyList<PhotoHit>(), search("beach", photos, lane = false).photos)
        assertEquals(0, photos.calls)
    }
}
