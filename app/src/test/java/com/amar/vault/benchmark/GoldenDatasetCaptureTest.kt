package com.amar.vault.benchmark

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The device-side `captured` dataset written by Dev Tools → Golden Queries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class GoldenDatasetCaptureTest {

    private lateinit var context: Context
    private lateinit var store: GoldenDatasetStore
    private lateinit var file: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = GoldenDatasetStore(context)
        file = File(File(context.filesDir, GoldenDatasetStore.DEVICE_DIR), "${GoldenDatasetStore.CAPTURED_DATASET}.json")
        file.delete()
    }

    @Test
    fun capturedCasesAreScorableRetrievalCasesInTheMergedDatasets() {
        assertTrue(store.capturedCases().isEmpty())

        store.appendCapturedCase("  बिजली का बिल  ", listOf("doc-1", "doc-2"), BenchmarkContentType.PDF)
        store.appendCapturedCase("passport", listOf("img-9"), BenchmarkContentType.IMAGE)

        val captured = store.loadAll().single { it.name == GoldenDatasetStore.CAPTURED_DATASET }.cases
        assertEquals(listOf("बिजली का बिल", "passport"), captured.map { it.queries.single() })
        assertEquals(listOf("doc-1", "doc-2"), captured[0].expectedResults)
        assertEquals("doc-1", captured[0].documentId)
        assertEquals(listOf("doc-1", "doc-2"), captured[0].expectedRank)
        assertTrue(captured.all { it.supportsRetrieval })
        assertEquals(2, captured.map { it.id }.distinct().size)
    }

    @Test
    fun removingACaseKeepsTheOthers() {
        val first = store.appendCapturedCase("invoice", listOf("a"), BenchmarkContentType.PDF)
        store.appendCapturedCase("ticket", listOf("b"), BenchmarkContentType.IMAGE)

        assertTrue(store.removeCapturedCase(first.id))
        assertFalse(store.removeCapturedCase(first.id))
        assertEquals(listOf("ticket"), store.capturedCases().map { it.queries.single() })
    }

    @Test
    fun aCorruptCapturedFileIsLeftUntouchedRatherThanOverwritten() {
        file.parentFile!!.mkdirs()
        file.writeText("{ not json")

        assertThrows(org.json.JSONException::class.java) {
            store.appendCapturedCase("invoice", listOf("a"), BenchmarkContentType.PDF)
        }
        assertEquals("{ not json", file.readText())
    }

    @Test
    fun blankQueriesAndEmptyAnswersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            store.appendCapturedCase("   ", listOf("a"), BenchmarkContentType.PDF)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.appendCapturedCase("invoice", emptyList(), BenchmarkContentType.PDF)
        }
    }
}
