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
 *   "script": "en | hi | mixed   (optional; derived from groundTruthText when omitted)",
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
    /**
     * Script this case's text is in: `en`, `hi`, or `mixed`. Optional — when blank,
     * [OcrScript.resolve] derives it from [groundTruthText] by Unicode block.
     *
     * Exists because the NPU-OCR acceptance gates are stated per script (Latin recognition
     * at INT8 is a solved problem; Devanagari is the risk model), and an aggregate CER
     * averaged across both scripts hides exactly the regression those gates exist to catch.
     */
    val script: String = "",
    /**
     * Image/layout condition this case represents: `clean`, `scanned`, `smalltext`, `tables`,
     * `multicolumn` (or any custom label). Optional.
     *
     * Unlike [script] this **cannot be derived** — it is a property of how the page was produced
     * and laid out, not of the characters in it, so an untagged case stays untagged and is counted
     * as such rather than guessed into a bucket.
     *
     * Exists so one E1 set answers "where does OCR break?" without transcribing a separate corpus
     * per condition. Transcription is the one resource in this project that cannot be scaled or
     * automated, so conditions are tags over shared ground truth, never separate datasets.
     */
    val stratum: String = "",
    val notes: String = "",
    /**
     * The right answers by what the files are called, most wanted first — for a set written
     * away from the device it is scored on. An item's id is made up on each device, so a set
     * that names ids only works on the phone it was entered on; a file is called the same
     * everywhere. Turned into that device's ids when the set is scored.
     */
    val expectedFiles: List<String> = emptyList(),
    /** What the case is there to test ("phrase", "typo", "hindi", …); scores are also given per kind. */
    val kind: String = "",
) {
    val supportsRetrieval: Boolean
        get() = queries.isNotEmpty() && (expectedResults.isNotEmpty() || expectedFiles.isNotEmpty())
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
            script = json.optString("script"),
            stratum = json.optString("stratum"),
            notes = json.optString("notes"),
            expectedFiles = json.optJSONArray("expectedFiles").toStringList(),
            kind = json.optString("kind"),
        )
    }
}

/**
 * Script classification for OCR scoring.
 *
 * Resolution order is explicit-then-derived: a case's `script` field wins, and only when it is
 * blank is the script derived from the ground-truth text. Derivation is a deterministic function
 * of characters actually present — not a guess about the metric — and every row records which
 * path was taken (`scriptSource`) so a mislabelled dataset is visible rather than silent.
 */
object OcrScript {
    const val EN = "en"
    const val HI = "hi"
    const val MIXED = "mixed"
    const val UNKNOWN = "unknown"

    /** Minority-script share above which a line counts as genuinely mixed rather than noise. */
    private const val MIXED_THRESHOLD = 0.10

    fun resolve(case: BenchmarkCase): Pair<String, String> {
        val explicit = case.script.trim().lowercase()
        if (explicit.isNotEmpty()) {
            val known = when (explicit) {
                EN, "latin", "english" -> EN
                HI, "devanagari", "hindi" -> HI
                MIXED -> MIXED
                else -> explicit
            }
            return known to "declared"
        }
        return derive(case.groundTruthText) to "derived"
    }

    /**
     * Devanagari block is U+0900–U+097F. Latin letters are counted via [Char.isLetter] restricted
     * to ASCII/Latin-1 ranges, so digits and punctuation — which are script-neutral and appear in
     * both — never decide the classification on their own.
     */
    fun derive(text: String): String {
        var devanagari = 0
        var latin = 0
        for (ch in text) {
            val cp = ch.code
            when {
                cp in 0x0900..0x097F && ch.isLetter() -> devanagari++
                cp < 0x0250 && ch.isLetter() -> latin++
            }
        }
        val total = devanagari + latin
        if (total == 0) return UNKNOWN
        val minorityShare = minOf(devanagari, latin).toDouble() / total
        if (devanagari > 0 && latin > 0 && minorityShare >= MIXED_THRESHOLD) return MIXED
        return if (devanagari >= latin) HI else EN
    }
}

/**
 * Image/layout condition buckets for OCR scoring — "where does OCR break?", answered from one
 * golden set instead of one corpus per condition.
 *
 * The alternative (separate E-sets per condition) multiplies the only cost that cannot be
 * automated: human transcription. Six conditions × two scripts × 100 lines is ~1,200 transcribed
 * lines for the same decision ~200 tagged lines can make.
 *
 * [CANONICAL] is always reported — as null + note when a bucket is empty — because an *absent*
 * condition is the finding. "We never measured a scanned page" must be visible in the report, not
 * inferred from a missing row.
 *
 * Camera / perspective / low-light are deliberately absent: those belong to the image path, which
 * ADR-0001 keeps on ML Kit. Add them when roadmap §9 decision 5 moves that path.
 */
object OcrStratum {
    const val CLEAN = "clean"
    const val SCANNED = "scanned"
    const val SMALLTEXT = "smalltext"
    const val TABLES = "tables"
    const val MULTICOLUMN = "multicolumn"
    const val UNTAGGED = "untagged"

    val CANONICAL: List<String> = listOf(CLEAN, SCANNED, SMALLTEXT, TABLES, MULTICOLUMN)

    /**
     * Normalizes a declared stratum. Returns [UNTAGGED] when blank — never guessed, because a
     * stratum is a property of the page's origin and layout, not of its text. Custom labels are
     * passed through so a dataset can add its own bucket without a code change.
     */
    fun resolve(case: BenchmarkCase): String {
        val raw = case.stratum.trim().lowercase().replace('-', '_').replace(" ", "")
        if (raw.isEmpty()) return UNTAGGED
        return when (raw) {
            "clean", "digital", "cleandigital" -> CLEAN
            "scanned", "scan", "noisy", "scannednoisy" -> SCANNED
            "smalltext", "small", "footnote", "footnotes" -> SMALLTEXT
            "tables", "table", "form", "forms" -> TABLES
            "multicolumn", "multi_column", "columns", "twocolumn" -> MULTICOLUMN
            else -> raw
        }
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
