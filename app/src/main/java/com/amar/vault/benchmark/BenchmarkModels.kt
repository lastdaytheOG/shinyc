package com.amar.vault.benchmark

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sprint 3C — permanent evaluation framework, core types.
 *
 * Ground rules (enforced by construction throughout this package):
 *  - Every metric value is MEASURED. A metric that cannot be measured on this device /
 *    with the current corpus is reported with `value = null` and a note saying why —
 *    never estimated, never invented.
 *  - The framework only OBSERVES production components through their existing public
 *    seams. It never modifies indexing, retrieval, ranking, OCR, or embeddings.
 */

// ── Golden dataset schema (Module 1) ─────────────────────────────────────────

/** Content types a benchmark case can describe. */
enum class BenchmarkContentType {
    PDF, SCREENSHOT, IMAGE, RECEIPT, NOTE, SAVED_LINK, MIXED_OCR;

    companion object {
        fun parse(raw: String): BenchmarkContentType =
            entries.firstOrNull { it.name.equals(raw.trim().replace('-', '_'), ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown contentType '$raw'")
    }
}

/**
 * One golden test case. JSON shape (all retrieval fields optional for OCR-only cases and
 * vice versa — a case contributes to whichever modules its fields support):
 *
 * ```json
 * {
 *   "id": "receipt-swiggy-001",
 *   "contentType": "RECEIPT",
 *   "documentId": "<VaultItem id or parentDocumentId of the indexed doc>",
 *   "queries": ["swiggy order june"],
 *   "expectedResults": ["<documentId>", "..."],
 *   "expectedRank": ["<most relevant documentId first>"],
 *   "groundTruthText": "<exact text for OCR accuracy scoring>",
 *   "mediaFile": "media/receipt_swiggy_001.png",
 *   "notes": "why this case exists"
 * }
 * ```
 */
data class BenchmarkCase(
    val id: String,
    val contentType: BenchmarkContentType,
    val documentId: String = "",
    val queries: List<String> = emptyList(),
    val expectedResults: List<String> = emptyList(),
    val expectedRank: List<String> = emptyList(),
    val groundTruthText: String = "",
    val mediaFile: String = "",
    val notes: String = "",
) {
    val supportsRetrieval: Boolean get() = queries.isNotEmpty() && expectedResults.isNotEmpty()
    val supportsOcr: Boolean get() = groundTruthText.isNotBlank() && mediaFile.isNotBlank()

    companion object {
        fun fromJson(json: JSONObject): BenchmarkCase = BenchmarkCase(
            id = json.getString("id"),
            contentType = BenchmarkContentType.parse(json.getString("contentType")),
            documentId = json.optString("documentId"),
            queries = json.optJSONArray("queries").toStringList(),
            expectedResults = json.optJSONArray("expectedResults").toStringList(),
            expectedRank = json.optJSONArray("expectedRank").toStringList(),
            groundTruthText = json.optString("groundTruthText"),
            mediaFile = json.optString("mediaFile"),
            notes = json.optString("notes"),
        )
    }
}

/** A named collection of cases (one dataset JSON file = one dataset). */
data class BenchmarkDataset(val name: String, val cases: List<BenchmarkCase>)

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { getString(it) }
}

// ── Suites (Module 2) ────────────────────────────────────────────────────────

enum class BenchmarkSuite { FULL, OCR, RETRIEVAL, EMBEDDING, PERFORMANCE }

// ── Report model ─────────────────────────────────────────────────────────────

/**
 * One measured (or explicitly unmeasured) metric. [value] == null means "could not be
 * measured" — [note] must say why. [higherIsBetter] drives regression direction.
 */
data class MetricValue(
    val name: String,
    val value: Double?,
    val unit: String,
    val higherIsBetter: Boolean,
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("value", value ?: JSONObject.NULL)
        .put("unit", unit)
        .put("higherIsBetter", higherIsBetter)
        .put("note", note)

    companion object {
        fun fromJson(json: JSONObject): MetricValue = MetricValue(
            name = json.getString("name"),
            value = if (json.isNull("value")) null else json.getDouble("value"),
            unit = json.optString("unit"),
            higherIsBetter = json.optBoolean("higherIsBetter", false),
            note = json.optString("note"),
        )
    }
}

/** One module's output: aggregate metrics + optional per-case rows for CSV/MD detail. */
data class BenchmarkSection(
    val id: String,
    val title: String,
    val metrics: List<MetricValue>,
    val rows: List<Map<String, String>> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("metrics", JSONArray(metrics.map { it.toJson() }))
        .put("rows", JSONArray(rows.map { JSONObject(it as Map<*, *>) }))

    companion object {
        fun fromJson(json: JSONObject): BenchmarkSection {
            val metrics = json.getJSONArray("metrics")
            val rows = json.optJSONArray("rows") ?: JSONArray()
            return BenchmarkSection(
                id = json.getString("id"),
                title = json.getString("title"),
                metrics = (0 until metrics.length()).map { MetricValue.fromJson(metrics.getJSONObject(it)) },
                rows = (0 until rows.length()).map { i ->
                    val o = rows.getJSONObject(i)
                    o.keys().asSequence().associateWith { k -> o.get(k).toString() }
                },
            )
        }
    }
}

/** A complete benchmark run. */
data class BenchmarkRunReport(
    val runId: String,
    val suite: BenchmarkSuite,
    val startedAtMs: Long,
    val durationMs: Long,
    val deviceModel: String,
    val sdkInt: Int,
    val corpusDocumentCount: Long,
    val sections: List<BenchmarkSection>,
    val notes: List<String> = emptyList(),
) {
    fun metric(sectionId: String, metricName: String): MetricValue? =
        sections.firstOrNull { it.id == sectionId }?.metrics?.firstOrNull { it.name == metricName }

    fun toJson(): JSONObject = JSONObject()
        .put("runId", runId)
        .put("suite", suite.name)
        .put("startedAtMs", startedAtMs)
        .put("durationMs", durationMs)
        .put("deviceModel", deviceModel)
        .put("sdkInt", sdkInt)
        .put("corpusDocumentCount", corpusDocumentCount)
        .put("sections", JSONArray(sections.map { it.toJson() }))
        .put("notes", JSONArray(notes))

    companion object {
        fun fromJson(json: JSONObject): BenchmarkRunReport {
            val sections = json.getJSONArray("sections")
            val notes = json.optJSONArray("notes") ?: JSONArray()
            return BenchmarkRunReport(
                runId = json.getString("runId"),
                suite = BenchmarkSuite.valueOf(json.getString("suite")),
                startedAtMs = json.getLong("startedAtMs"),
                durationMs = json.getLong("durationMs"),
                deviceModel = json.optString("deviceModel"),
                sdkInt = json.optInt("sdkInt"),
                corpusDocumentCount = json.optLong("corpusDocumentCount"),
                sections = (0 until sections.length()).map { BenchmarkSection.fromJson(sections.getJSONObject(it)) },
                notes = (0 until notes.length()).map { notes.getString(it) },
            )
        }
    }
}

// ── Regression model (Module 11) ─────────────────────────────────────────────

enum class RegressionVerdict { REGRESSED, IMPROVED, UNCHANGED, NOT_COMPARABLE }

data class RegressionFinding(
    val sectionId: String,
    val metricName: String,
    val baseline: Double?,
    val current: Double?,
    val deltaPercent: Double?,
    val verdict: RegressionVerdict,
    val note: String = "",
)

// ── Shared math helpers (used by several modules) ────────────────────────────

object BenchmarkMath {
    fun percentile(sortedMs: List<Long>, p: Double): Double? {
        if (sortedMs.isEmpty()) return null
        val idx = ((sortedMs.size - 1) * p).toInt().coerceIn(0, sortedMs.size - 1)
        return sortedMs[idx].toDouble()
    }

    fun median(sortedMs: List<Long>): Double? = percentile(sortedMs, 0.5)

    fun mean(values: List<Long>): Double? = if (values.isEmpty()) null else values.average()

    /** Standard Levenshtein distance; used for character/word accuracy in the OCR module. */
    fun <T> levenshtein(a: List<T>, b: List<T>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var prev = IntArray(b.size + 1) { it }
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.size]
    }
}
