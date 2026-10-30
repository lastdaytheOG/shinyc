package com.amar.vault

import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.amar.vault.photos.photoSearchOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * The indexing engine timed whole, as the app runs it, and what came out of it written down, so
 * two builds can be compared on speed and on being the same. Nothing here touches the engine: it
 * starts the work the app starts and waits for it.
 *
 *     adb shell am instrument -w -e class com.amar.vault.IndexingEngineBenchDeviceTest \
 *         -e bench scan -e folder BenchP12 -e label debug \
 *         com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `scan` runs the bulk scan over one gallery folder; `scan` again over the same folder times a scan
 * that has nothing new to do; `import` reads the files in `<external files>/bench/docs` through the
 * document queue; `digest` writes what the vault holds.
 *
 * Results go to `<external files>/bench/`, which adb can read on a build that cannot be `run-as`:
 * timings and counts in `<label>.json`, hashes of what is stored in `<label>.digest.jsonl`. No text
 * read off anything leaves the phone in either.
 *
 * Run it on a vault with nothing in it (`pm clear`), and never on a phone's own vault: it changes
 * the scan's folders. The bench build has its own package and data (`-PbenchApp`).
 */
@RunWith(AndroidJUnit4::class)
class IndexingEngineBenchDeviceTest {

    private val args get() = InstrumentationRegistry.getArguments()
    private val app by lazy { InstrumentationRegistry.getInstrumentation().targetContext.applicationContext }
    private val out by lazy { File(app.getExternalFilesDir(null) ?: app.filesDir, "bench").apply { mkdirs() } }
    private val db by lazy { VaultDatabase.get(app) }

    private fun heapMb() = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1 shl 20)
    private fun nativeMb() = Debug.getNativeHeapAllocatedSize() / (1 shl 20)
    private fun gcCount() = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1L
    private fun gcMs() = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: -1L

    /** What is the same for every result file. */
    private fun header(label: String) = JSONObject().apply {
        put("label", label)
        put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("sdk", Build.VERSION.SDK_INT)
        put("abi", Build.SUPPORTED_ABIS.firstOrNull())
        put("debuggable", (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
    }

    @Test
    fun scan() = runBlocking {
        assumeTrue("run with -e bench scan", args.getString("bench") == "scan")
        val label = args.getString("label") ?: "run"
        val folder = args.getString("folder")!!
        ScanPreferences.save(app, ScanPreferences.Prefs(onboardingDone = true, scanScreenshots = false, customFolders = setOf(folder)))

        // What the app does when it starts must not be counted: the models copied out and loaded,
        // the keyword engine filled.
        val photos = photoSearchOf(app)
        photos.install()
        val encoders = photos.encodersForBenchmark()
        delay(args.getString("settle")?.toLong() ?: 4000L)

        // -e fill N: a vault that already holds N pieces of documents; -e fillPictures N: N pictures.
        args.getString("fill")?.toInt()?.let { fill(it) }
        args.getString("fillPictures")?.toInt()?.let { fillPictures(it) }
        // -e observe true: the whole table watched, as the photo screen's ViewModel does once opened.
        val watcher = if (args.getString("observe") == "true") {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { db.vaultDao().getAllItems().collect { } }
        } else null

        val before = db.vaultDao().getCount()
        val vectorsBefore = db.photoVectorDao().countOf(photos.modelId.orEmpty())
        val gc0 = gcCount(); val gcMs0 = gcMs()
        val power = app.getSystemService(PowerManager::class.java)
        val thermal0 = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
        var peakHeap = 0L; var peakNative = 0L; var worst = thermal0
        val cpuStart = android.os.Process.getElapsedCpuTime()

        val t0 = System.nanoTime()
        BulkScanWorker.enqueue(app)
        val wm = WorkManager.getInstance(app)
        var state: WorkInfo.State
        var outputData = androidx.work.Data.EMPTY
        var workEndedAt = 0L
        while (true) {
            val info = wm.getWorkInfosForUniqueWork("bulk_scan").get().lastOrNull()
            state = info?.state ?: WorkInfo.State.ENQUEUED
            peakHeap = maxOf(peakHeap, heapMb()); peakNative = maxOf(peakNative, nativeMb())
            if (Build.VERSION.SDK_INT >= 29) worst = maxOf(worst, power.currentThermalStatus)
            if (state.isFinished) { outputData = info!!.outputData; workEndedAt = System.nanoTime(); break }
            delay(50)
        }
        // The last vectors are made just after the scan's last photo has been read.
        val expected = args.getString("expect")?.toInt()
        var lastCount = -1
        var stable = 0
        while (stable < 20) {
            val n = db.photoVectorDao().countOf(photos.modelId.orEmpty())
            stable = if (n == lastCount) stable + 1 else 0
            lastCount = n
            delay(50)
        }
        watcher?.cancel()
        val settledAt = System.nanoTime() - 1_000_000_000L
        val totalMs = (maxOf(settledAt, workEndedAt) - t0) / 1e6
        val cpuMs = android.os.Process.getElapsedCpuTime() - cpuStart

        val rows = db.vaultDao().getCount() - before
        val pictures = outputData.getInt("total", -1)
        val result = header(label).apply {
            put("bench", "scan").put("folder", folder)
            put("state", state.name)
            put("gallery_pictures", pictures).put("indexed_reported", outputData.getInt("indexed", -1))
            put("new_rows", rows)
            put("new_vectors", lastCount - vectorsBefore)
            put("models_loaded", encoders != null)
            put("total_ms", totalMs)
            put("ms_per_picture", if (pictures > 0) totalMs / pictures else 0.0)
            put("cpu_ms_process", cpuMs)
            put("cpu_share_of_core", cpuMs / totalMs)
            put("gc_count", gcCount() - gc0).put("gc_ms", gcMs() - gcMs0)
            put("peak_heap_mb", peakHeap).put("peak_native_mb", peakNative)
            put("thermal_start", thermal0).put("thermal_worst", worst)
            put("metrics", metrics())
        }
        File(out, "$label.json").writeText(result.toString(1))
        writeDigest(label)
        assertTrue("the scan ended ${state.name}", state == WorkInfo.State.SUCCEEDED)
        if (expected != null) assertTrue("expected $expected pictures, the gallery gave $pictures", pictures == expected)
    }

    private suspend fun fillPictures(rows: Int) {
        val text = (1..40).joinToString(" ") { "word$it" }
        (0 until rows).chunked(1000).forEach { some ->
            db.vaultDao().insertAll(some.map { i ->
                VaultItem(
                    id = "bench-picture-$i", uri = "file:///bench/picture$i.jpg", ocrText = "$i $text", lang = "en",
                    itemType = ItemType.PHOTO, sourceFile = "picture$i.jpg", timestamp = 0L, pHash = 1_000_000L + i,
                )
            })
        }
    }

    private suspend fun fill(rows: Int) {
        val text = (1..170).joinToString(" ") { "word$it" }
        val documentId = "bench-fill"
        db.vaultDocumentDao().upsert(VaultDocument(documentId, "file:///bench/fill.pdf", "fill.pdf", ItemType.PDF, "bench-fill", rows / 4, rows, 0L))
        (0 until rows).chunked(1000).forEach { some ->
            db.vaultDao().insertAll(some.map { i ->
                VaultItem(
                    id = "${documentId}_chunk$i", uri = "file:///bench/fill.pdf", ocrText = "$i $text", lang = "en", itemType = ItemType.PDF,
                    pageNum = i / 4 + 1, sourceFile = "fill.pdf", timestamp = 0L, contentHash = "bench-fill",
                    parentDocumentId = documentId, chunkIndex = i, totalChunks = 0,
                )
            })
        }
    }

    @Test
    fun import() = runBlocking {
        assumeTrue("run with -e bench import", args.getString("bench") == "import")
        val label = args.getString("label") ?: "run"
        val dir = File(out, "docs")
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        assertTrue("put documents in ${dir.path}", files.isNotEmpty())
        delay(args.getString("settle")?.toLong() ?: 4000L)

        val docs = JSONArray()
        val gc0 = gcCount(); val gcMs0 = gcMs()
        val t00 = System.nanoTime()
        for (file in files) {
            val before = db.vaultDao().getCount()
            val t0 = System.nanoTime()
            val result = DocumentIndexer.getInstance(app).indexDocument(Uri.fromFile(file), "application/pdf", displayName = file.name)
            val ms = (System.nanoTime() - t0) / 1e6
            val document = db.vaultDocumentDao().getAll().firstOrNull { it.name == file.name }
            docs.put(JSONObject().apply {
                put("file", file.name).put("bytes", file.length())
                put("result", result::class.simpleName)
                put("ms", ms)
                put("pages", document?.pageCount ?: -1)
                put("pieces", db.vaultDao().getCount() - before)
                document?.pageCount?.takeIf { it > 0 }?.let { put("ms_per_page", ms / it) }
            })
        }
        File(out, "$label.json").writeText(header(label).apply {
            put("bench", "import")
            put("total_ms", (System.nanoTime() - t00) / 1e6)
            put("gc_count", gcCount() - gc0).put("gc_ms", gcMs() - gcMs0)
            put("documents", docs)
            put("metrics", metrics())
        }.toString(1))
        writeDigest(label)
    }

    @Test
    fun digest() = runBlocking {
        assumeTrue("run with -e bench digest", args.getString("bench") == "digest")
        writeDigest(args.getString("label") ?: "run")
    }

    private fun metrics(): JSONObject = JSONObject().apply {
        for ((k, v) in IndexMetrics.snapshot()) put(k, v.toString())
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * One line per thing stored, keyed by what it was made from (a picture's file name, a piece's
     * document and index) and not by its random id: every column of the row that is not a clock or
     * an id, its metadata and its photo vector, as hashes. Two runs that stored the same have the
     * same file once sorted.
     */
    private suspend fun writeDigest(label: String) {
        val items = db.vaultDao().getAll()
        val model = photoSearchOf(app).modelId.orEmpty()
        val vectors = db.photoVectorDao().allOf(model).associateBy { it.itemId }
        val metaDao = db.vaultMetadataDao()
        val documents = db.vaultDocumentDao().getAll().associateBy { it.id }
        val lines = items.map { item ->
            val key = item.parentDocumentId?.let { "${documents[it]?.name ?: "?"}#${item.chunkIndex}" } ?: item.sourceFile.ifBlank { item.uri }
            val meta = metaDao.getByItemId(item.id)
                .map { "${it.type}|${it.value}|${it.confidence}|${it.source}|${it.numericValue}|${it.timestampValue}|${it.extractionVersion}" }.sorted()
            val v = vectors[item.id]
            JSONObject().apply {
                put("key", key)
                put("type", item.itemType.stored)
                put("text", sha(item.ocrText.toByteArray()))
                put("text_len", item.ocrText.length)
                put("lang", item.lang)
                put("page", item.pageNum)
                put("phash", item.pHash)
                put("tags", sha(item.tags.toByteArray()))
                put("qr", item.qrPayload?.let { sha(it.toByteArray()) } ?: "")
                put("meta", sha(meta.joinToString("\n").toByteArray()))
                put("meta_rows", meta.size)
                put("vector", v?.let { sha(it.vector) + ":" + it.scale } ?: "")
            }.toString()
        }.sorted()
        File(out, "$label.digest.jsonl").writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
