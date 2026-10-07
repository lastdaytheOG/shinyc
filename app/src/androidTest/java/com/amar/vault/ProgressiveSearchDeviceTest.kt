package com.amar.vault

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.RetrievalStage
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Progressive search against the REAL stack on a device/emulator: real Room (SQLite), the real
 * native BM25 engine and the app's own Hilt singletons — the parts the JVM tests replace with
 * fakes. Seeds a book-sized corpus of chunk rows, then checks the stage contract and logs how
 * long each stage took (tag `ProgressiveDeviceTest`).
 *
 * NOT YET RUN: written 2026-10-04 with no phone attached, and the only emulator had too little
 * free storage to install the app. It compiles; its assertions are unproven on a device.
 *
 * Run it on an emulator or a spare phone only. Gradle's `connectedDebugAndroidTest` uninstalls
 * the app when it finishes, which deletes everything the app has indexed on that device.
 */
@RunWith(AndroidJUnit4::class)
class ProgressiveSearchDeviceTest {

    private companion object {
        const val TAG = "ProgressiveDeviceTest"
        const val PREFIX = "devtest-"
        const val CHUNKS = 3_000
        const val WORDS_PER_CHUNK = 180
        val FILLER = listOf(
            "report", "chapter", "section", "figure", "table", "student", "government", "notice",
            "amount", "number", "office", "district", "school", "application", "certificate",
            "विद्यालय", "सरकार", "सूचना", "परीक्षा", "प्रमाण", "आवेदन", "जिला", "कार्यालय",
        )
    }

    private val services by lazy {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java)
    }

    @Before
    fun seedCorpus() = runBlocking {
        val random = Random(7)
        val rows = (0 until CHUNKS).map { i ->
            val words = MutableList(WORDS_PER_CHUNK) { FILLER[random.nextInt(FILLER.size)] }
            // A few chunks carry the phrase the test searches for; one carries a typo of it.
            if (i % 500 == 3) words[10] = "electricity bill payment receipt"
            if (i == 1234) words[20] = "electricty bil"
            VaultItem(
                id = "$PREFIX${"%05d".format(i)}",
                uri = "content://devtest/book.pdf",
                ocrText = words.joinToString(" "),
                lang = "en",
                itemType = ItemType.PDF,
                pageNum = i / 3,
                sourceFile = "devtest-book.pdf",
                timestamp = 1_700_000_000_000L + i,
                parentDocumentId = "${PREFIX}book",
                chunkIndex = i,
                totalChunks = CHUNKS,
            )
        }
        rows.chunked(500).forEach { services.database().vaultDao().insertAll(it) }
        rows.forEach { services.bm25Index().addDocument(it.id, it.ocrText) }
    }

    @After
    fun removeCorpus() = runBlocking {
        services.database().openHelper.writableDatabase
            .execSQL("DELETE FROM vault_items WHERE id LIKE '$PREFIX%'")
    }

    @Test
    fun keywordStageArrivesFirstAndFinalMatchesRetrieve() = runBlocking {
        val request = RetrievalRequest("electricity bill")
        services.retrievalService().retrieve(request) // warm-up: JIT, SQLite page cache

        val updates = services.retrievalService().retrieveProgressive(request).toList()
        val final = services.retrievalService().retrieve(request)

        updates.forEach { Log.i(TAG, "stage=${it.stage} at=${it.elapsedMs}ms items=${it.result.items.size}") }
        Log.i(TAG, "corpus=$CHUNKS chunks x $WORDS_PER_CHUNK words; top=${final.items.take(3).map { it.id }}")

        assertEquals(RetrievalStage.KEYWORD, updates.first().stage)
        assertEquals(RetrievalStage.FINAL, updates.last().stage)
        assertEquals(final.items.map { it.id }, updates.last().result.items.map { it.id })
        assertTrue("phrase chunks must be found", final.items.any { "electricity bill" in it.ocrText })
        assertTrue(
            "keyword stage must not wait for the full-corpus scan",
            updates.first().elapsedMs <= updates.last().elapsedMs,
        )
    }

    @Test
    fun bm25RankOrderIsWhatFusionSees() = runBlocking {
        val query = "electricity"
        val engineOrder = services.lexicalRetriever().bm25(query, 50)
        val hydrated = services.searchRepository().getByIds(engineOrder).map { it.id }
        Log.i(TAG, "bm25 engine order=${engineOrder.take(5)} | SQLite IN-list order=${hydrated.take(5)}")

        // SQLite answers the IN-list lookup in primary-key order.
        assertEquals(engineOrder.sorted(), hydrated)
        // With no substring/fuzzy contribution to break ties, the KEYWORD stage must follow the
        // engine's order rather than that alphabetical one.
        val keyword = services.retrievalService().retrieveProgressive(RetrievalRequest(query)).toList().first()
        assertEquals(RetrievalStage.KEYWORD, keyword.stage)
        val expectedOrder = engineOrder.filter { id -> keyword.result.items.any { it.id == id } }
        assertEquals(expectedOrder, keyword.result.items.map { it.id })
    }
}
