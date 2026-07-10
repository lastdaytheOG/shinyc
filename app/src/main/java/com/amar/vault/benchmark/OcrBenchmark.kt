package com.amar.vault.benchmark

import android.content.Context
import com.amar.vault.indexing.ImageContentExtractor

/**
 * Module 4 — OCR accuracy & latency.
 *
 * Runs the REAL production OCR ensemble ([ImageContentExtractor.extract] — the exact
 * stage the image indexing path uses, unmodified) over every golden case that supplies
 * a media file + hand-verified ground truth, and scores:
 *
 *  - character accuracy = 1 − charLevenshtein / max(len)      (whitespace-normalized)
 *  - word accuracy      = 1 − wordLevenshtein / max(words)    (lowercased tokens)
 *  - latency            = wall-clock per image
 *
 * OCR confidence is NOT reported: the production extractor merges five engine passes
 * and does not expose per-pass confidences. The metric exists in the report with a
 * null value so the gap is visible instead of fabricated.
 *
 * Before/After comparison comes from the regression runner over stored reports —
 * this module only measures the "now".
 */
class OcrBenchmark(
    private val context: Context,
    private val datasetStore: GoldenDatasetStore,
) {

    // Same class the production pipeline uses; instantiated here only to *call* it.
    private val extractor by lazy { ImageContentExtractor(context) }

    suspend fun run(cases: List<BenchmarkCase>): BenchmarkSection {
        val ocrCases = cases.filter { it.supportsOcr }
        val rows = mutableListOf<Map<String, String>>()
        val charAcc = mutableListOf<Double>()
        val wordAcc = mutableListOf<Double>()
        val latencies = mutableListOf<Long>()
        val byType = mutableMapOf<BenchmarkContentType, MutableList<Double>>()
        var skipped = 0

        for (case in ocrCases) {
            val bitmap = datasetStore.loadMediaBitmap(case)
            if (bitmap == null) {
                skipped++
                rows.add(mapOf("caseId" to case.id, "status" to "SKIPPED", "reason" to "media file missing/undecodable: ${case.mediaFile}"))
                continue
            }
            val t0 = System.currentTimeMillis()
            val ocrText = try {
                extractor.extract(bitmap).ocrText
            } finally {
                bitmap.recycle()
            }
            val elapsed = System.currentTimeMillis() - t0

            val ca = characterAccuracy(ocrText, case.groundTruthText)
            val wa = wordAccuracy(ocrText, case.groundTruthText)
            charAcc.add(ca); wordAcc.add(wa); latencies.add(elapsed)
            byType.getOrPut(case.contentType) { mutableListOf() }.add(ca)
            rows.add(mapOf(
                "caseId" to case.id, "status" to "SCORED", "contentType" to case.contentType.name,
                "charAccuracy" to "%.4f".format(java.util.Locale.US, ca),
                "wordAccuracy" to "%.4f".format(java.util.Locale.US, wa),
                "latencyMs" to elapsed.toString(),
            ))
        }

        val none = charAcc.isEmpty()
        val noneNote = if (none) "no OCR cases with media + ground truth" else ""
        val sorted = latencies.sorted()

        val metrics = mutableListOf(
            MetricValue("charAccuracy.avg", charAcc.avgOrNull(), "ratio", true, noneNote),
            MetricValue("wordAccuracy.avg", wordAcc.avgOrNull(), "ratio", true, noneNote),
            MetricValue("latency.avg", BenchmarkMath.mean(latencies), "ms", false, noneNote),
            MetricValue("latency.p50", BenchmarkMath.percentile(sorted, 0.50), "ms", false, noneNote),
            MetricValue("latency.p95", BenchmarkMath.percentile(sorted, 0.95), "ms", false, noneNote),
            MetricValue("latency.p99", BenchmarkMath.percentile(sorted, 0.99), "ms", false, noneNote),
            MetricValue("latency.max", sorted.lastOrNull()?.toDouble(), "ms", false, noneNote),
            MetricValue("confidence.avg", null, "ratio", true,
                "not exposed by the production OCR ensemble (5-pass merge discards per-pass confidence)"),
            MetricValue("cases.scored", charAcc.size.toDouble(), "count", true),
            MetricValue("cases.skipped", skipped.toDouble(), "count", false),
        )
        // Per-content-type accuracy (screenshots / PDFs-as-images / photos / receipts).
        for ((type, values) in byType) {
            metrics.add(MetricValue("charAccuracy.${type.name.lowercase()}", values.average(), "ratio", true))
        }

        return BenchmarkSection(
            id = "ocr",
            title = "OCR accuracy & latency (production ImageContentExtractor)",
            metrics = metrics,
            rows = rows,
        )
    }

    private fun characterAccuracy(ocr: String, truth: String): Double {
        val a = normalize(ocr).toList()
        val b = normalize(truth).toList()
        val maxLen = maxOf(a.size, b.size)
        if (maxLen == 0) return 0.0
        return (1.0 - BenchmarkMath.levenshtein(a, b).toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    private fun wordAccuracy(ocr: String, truth: String): Double {
        val a = normalize(ocr).split(' ').filter { it.isNotEmpty() }
        val b = normalize(truth).split(' ').filter { it.isNotEmpty() }
        val maxLen = maxOf(a.size, b.size)
        if (maxLen == 0) return 0.0
        return (1.0 - BenchmarkMath.levenshtein(a, b).toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun List<Double>.avgOrNull(): Double? = if (isEmpty()) null else average()
}
