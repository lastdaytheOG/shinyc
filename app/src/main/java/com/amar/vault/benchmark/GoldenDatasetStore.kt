package com.amar.vault.benchmark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.amar.vault.VaultLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Module 1 — Golden dataset loader.
 *
 * Datasets are JSON files, one dataset per file:
 *
 * ```json
 * { "name": "starter", "cases": [ { ...BenchmarkCase schema... } ] }
 * ```
 *
 * Two locations are merged (later wins on duplicate dataset names):
 *  1. APK assets:  `assets/benchmark/GoldenDataset/<name>.json`  — versioned with the repo.
 *  2. Device dir:  `filesDir/benchmark/GoldenDataset/<name>.json` — adb-pushable, so cases
 *     can be added on a test device without rebuilding (same workflow as Dev Tools
 *     manual indexing). Media referenced by `mediaFile` resolves relative to the case's
 *     own location (asset dir or device dir).
 *
 * Malformed files/cases are skipped with a logged warning — a bad case must never
 * silently poison a benchmark run.
 */
class GoldenDatasetStore(private val context: Context) {

    data class MediaCheck(
        val usable: Boolean,
        val code: String,
        val detail: String,
    )

    companion object {
        const val ASSET_DIR = "benchmark/GoldenDataset"
        const val DEVICE_DIR = "benchmark/GoldenDataset"

        /** Device-side dataset written by Dev Tools → Golden Queries. */
        const val CAPTURED_DATASET = "captured"
    }

    private val deviceDir: File get() = File(context.filesDir, DEVICE_DIR)

    /** All datasets from both locations. Device datasets override same-named asset ones. */
    fun loadAll(): List<BenchmarkDataset> {
        val byName = LinkedHashMap<String, BenchmarkDataset>()
        loadFromAssets().forEach { byName[it.name] = it }
        loadFromDevice().forEach { byName[it.name] = it }
        return byName.values.toList()
    }

    fun allCases(): List<BenchmarkCase> = loadAll().flatMap { it.cases }

    /**
     * Resolve a case's media file to a decoded bitmap, checking the device dir first,
     * then assets. Returns null (never throws) if absent or undecodable.
     */
    fun loadMediaBitmap(case: BenchmarkCase): Bitmap? {
        if (case.mediaFile.isBlank()) return null
        val deviceFile = File(deviceDir, case.mediaFile)
        if (deviceFile.exists()) {
            return runCatching { BitmapFactory.decodeFile(deviceFile.absolutePath) }.getOrNull()
        }
        return runCatching {
            context.assets.open("$ASSET_DIR/${case.mediaFile}").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    /**
     * Cheap, allocation-free media validation for release gates.  It deliberately checks the
     * same precedence as [loadMediaBitmap]: a broken device override must not silently fall
     * back to an asset and make the benchmark appear valid under a different input.
     */
    fun checkMedia(case: BenchmarkCase): MediaCheck {
        if (case.mediaFile.isBlank()) {
            return MediaCheck(false, "OCR_MEDIA_PATH_MISSING", "mediaFile is blank")
        }
        val deviceFile = File(deviceDir, case.mediaFile)
        if (deviceFile.exists()) {
            return checkBitmap(
                open = { deviceFile.inputStream() },
                location = "device:${deviceFile.absolutePath}",
            )
        }
        val assetPath = "$ASSET_DIR/${case.mediaFile}"
        return runCatching { context.assets.open(assetPath).close(); true }.fold(
            onSuccess = {
                checkBitmap(
                    open = { context.assets.open(assetPath) },
                    location = "asset:$assetPath",
                )
            },
            onFailure = {
                MediaCheck(false, "OCR_MEDIA_MISSING", "not found at device:${deviceFile.absolutePath} or asset:$assetPath")
            },
        )
    }

    private fun checkBitmap(open: () -> java.io.InputStream, location: String): MediaCheck {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val decoded = runCatching { open().use { BitmapFactory.decodeStream(it, null, options) } }.isSuccess
        return if (decoded && options.outWidth > 0 && options.outHeight > 0) {
            MediaCheck(true, "OCR_MEDIA_READY", "$location (${options.outWidth}x${options.outHeight})")
        } else {
            MediaCheck(false, "OCR_MEDIA_UNDECODABLE", "$location is not a decodable bitmap")
        }
    }

    private fun loadFromAssets(): List<BenchmarkDataset> {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull() ?: emptyList()
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            runCatching {
                val text = context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
                parseDataset(name.removeSuffix(".json"), text)
            }.onFailure {
                VaultLog.w("GoldenDataset", "Skipping asset dataset $name: ${it.message}")
            }.getOrNull()
        }
    }

    // ── Captured dataset (Dev Tools → Golden Queries) ────────────────────────────────────

    private val capturedFile: File get() = File(deviceDir, "$CAPTURED_DATASET.json")

    /** Cases written by the in-app capture screen, oldest first. Empty when none exist yet. */
    fun capturedCases(): List<BenchmarkCase> =
        if (!capturedFile.exists()) emptyList()
        else runCatching { parseDataset(CAPTURED_DATASET, capturedFile.readText()).cases }.getOrDefault(emptyList())

    /**
     * Append one retrieval case to the device-side `captured` dataset: a query a person typed
     * and the documents that person marked as the right answers ([expectedDocumentIds], most
     * relevant first). Ids are `parentDocumentId ?: id` — the same collapse
     * [RetrievalEvaluator] scores on. The expected set is a human choice; it must never be
     * filled in from the ranking that is being evaluated.
     *
     * @throws org.json.JSONException if an existing captured file is not valid JSON — it is
     *         left untouched rather than overwritten, so hand edits are never silently lost.
     */
    fun appendCapturedCase(
        query: String,
        expectedDocumentIds: List<String>,
        contentType: BenchmarkContentType,
        notes: String = "",
    ): BenchmarkCase {
        require(query.isNotBlank()) { "query is blank" }
        require(expectedDocumentIds.isNotEmpty()) { "no expected documents" }
        val root = readCaptured()
        val cases = root.getJSONArray("cases")
        val case = JSONObject()
            .put("id", "captured-${System.currentTimeMillis()}-${cases.length() + 1}")
            .put("contentType", contentType.name)
            .put("documentId", expectedDocumentIds.first())
            .put("queries", JSONArray(listOf(query.trim())))
            .put("expectedResults", JSONArray(expectedDocumentIds))
            .put("expectedRank", JSONArray(expectedDocumentIds))
            .put("notes", notes)
        cases.put(case)
        writeCaptured(root)
        return BenchmarkCase.fromJson(case)
    }

    /** Remove one captured case by id. Returns false when no such case exists. */
    fun removeCapturedCase(caseId: String): Boolean {
        if (!capturedFile.exists()) return false
        val root = readCaptured()
        val cases = root.getJSONArray("cases")
        val kept = JSONArray()
        var removed = false
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            if (case.optString("id") == caseId) removed = true else kept.put(case)
        }
        if (removed) writeCaptured(root.put("cases", kept))
        return removed
    }

    private fun readCaptured(): JSONObject =
        if (!capturedFile.exists()) JSONObject().put("name", CAPTURED_DATASET).put("cases", JSONArray())
        else JSONObject(capturedFile.readText()).also { it.getJSONArray("cases") }

    private fun writeCaptured(root: JSONObject) {
        deviceDir.mkdirs()
        val temp = File(deviceDir, "$CAPTURED_DATASET.json.tmp")
        temp.writeText(root.toString(2))
        val target = capturedFile
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            throw java.io.IOException("could not write ${target.absolutePath}")
        }
    }

    private fun loadFromDevice(): List<BenchmarkDataset> {
        val files = deviceDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                parseDataset(file.name.removeSuffix(".json"), file.readText())
            }.onFailure {
                VaultLog.w("GoldenDataset", "Skipping device dataset ${file.name}: ${it.message}")
            }.getOrNull()
        }
    }

    private fun parseDataset(fallbackName: String, text: String): BenchmarkDataset {
        val json = JSONObject(text)
        val cases = json.getJSONArray("cases")
        val parsed = (0 until cases.length()).mapNotNull { i ->
            runCatching { BenchmarkCase.fromJson(cases.getJSONObject(i)) }.onFailure {
                VaultLog.w("GoldenDataset", "Skipping malformed case #$i in $fallbackName: ${it.message}")
            }.getOrNull()
        }
        return BenchmarkDataset(name = json.optString("name").ifBlank { fallbackName }, cases = parsed)
    }
}
