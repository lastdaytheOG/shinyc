package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalResult
import com.amar.vault.retrieval.SearchableName
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeaningSearchControlDeviceTest {

    private companion object {
        const val PREFIX = "meaningcontrol-"
    }

    private val services by lazy {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java)
    }

    private fun page(doc: String, text: String, file: String) = VaultItem(
        id = "$PREFIX${doc}_chunk0", uri = "content://meaningcontrol/$doc", ocrText = text, lang = "en",
        itemType = ItemType.PDF, pageNum = 1, sourceFile = file, timestamp = 1_700_000_000_000L,
        parentDocumentId = "$PREFIX$doc", chunkIndex = 0,
    )

    private val corpus = listOf(
        page("lease", "The landlord agrees to return the security amount within thirty days after the tenant vacates the flat.", "scan_0042.pdf"),
        page("gpu", "Graphics cards run thousands of small threads at once, and memory bandwidth often limits how fast a kernel finishes.", "notes_7.pdf"),
        page("power", "बिजली का बिल हर महीने की पाँच तारीख तक जमा करना होता है, वरना देर से भुगतान का शुल्क लगता है।", "IMG_2210.pdf"),
        page("exam", "Students must bring the hall ticket and a photo identity document to the examination centre by 9 am.", "doc_88.pdf"),
        page("gas", "A household can claim back the cylinder deposit by returning the subscription voucher at the distributor's office.", "file_3.pdf"),
    )

    private val searches = listOf(
        "rent deposit refund timeline" to "lease",
        "gpu architecture" to "gpu",
        "electricity bill due date" to "power",
        "things needed for test day" to "exam",
        "refund for lpg connection" to "gas",
    )

    @Before
    fun seed() = runBlocking {
        services.database().vaultDao().insertAll(corpus)
        corpus.forEach { services.bm25Index().addDocument(it.id, "${it.ocrText} ${it.tags} ${SearchableName.of(it)}") }
        val search = services.photoSearch()
        if (search.isReady) com.amar.vault.vectors.TextVectorIndexer(services.database(), search).run(2)
    }

    @After
    fun clean() = runBlocking {
        services.bm25Index().removeDocuments(corpus.map { it.id })
        services.database().openHelper.writableDatabase.execSQL("DELETE FROM text_vectors WHERE rowId LIKE '$PREFIX%'")
        services.database().openHelper.writableDatabase.execSQL("DELETE FROM vault_items WHERE id LIKE '$PREFIX%'")
        services.photoSearch().forgetRows(corpus.map { it.id })
    }

    private fun firstFive(result: RetrievalResult): List<String> =
        result.meaning?.shown.orEmpty().map { it.item.parentDocumentId ?: it.item.id }.take(5)

    @Test
    fun aDocumentIsFoundByWhatItMeans() = runBlocking {
        val detail = StringBuilder()
        val misses = searches.filter { (query, doc) ->
            val result = services.retrievalService().retrieve(RetrievalRequest.forResultList(query))
            val missed = "$PREFIX$doc" !in firstFive(result)
            if (missed) detail.append(" | ").append(doc).append(": ").append(result.meaning?.closest?.take(3)?.joinToString { m ->
                "%s %.3f m%.3f".format(m.item.id.removePrefix(PREFIX).substringBefore('_'), m.score, m.margin)
            })
            missed
        }
        assertTrue("not found by meaning: ${misses.map { it.second }}$detail", misses.isEmpty())
    }
}
