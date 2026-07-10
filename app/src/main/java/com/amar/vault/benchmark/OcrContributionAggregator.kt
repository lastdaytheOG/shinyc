package com.amar.vault.benchmark

import com.amar.vault.indexing.OcrImageReport
import com.amar.vault.indexing.OcrStrategy
import java.util.Locale

/**
 * Sprint E1 — shared aggregation for OCR strategy-contribution reports.
 *
 * Used by BOTH evaluation modes so they compute identically:
 *  - GoldenDataset mode ([OcrContributionBenchmark]) — synthetic corpus.
 *  - Production Evaluation Mode — reports captured from REAL indexing ([OcrInstrumentation] buffer).
 *
 * Pure: turns a list of per-image reports into benchmark metrics + rows. Nothing is estimated —
 * empty input yields null metrics with an explanatory note, never invented numbers.
 */
object OcrContributionAggregator {

    fun section(
        reports: List<OcrImageReport>,
        skipped: Int,
        id: String,
        title: String,
        emptyNote: String,
    ): BenchmarkSection = BenchmarkSection(
        id = id,
        title = title,
        metrics = metrics(reports, skipped, emptyNote),
        rows = rows(reports),
    )

    fun metrics(reports: List<OcrImageReport>, skipped: Int, emptyNote: String): List<MetricValue> {
        val metrics = mutableListOf<MetricValue>()
        val none = reports.isEmpty()

        metrics.add(MetricValue("images.evaluated", reports.size.toDouble(), "count", true, if (none) emptyNote else ""))
        metrics.add(MetricValue("images.skipped", skipped.toDouble(), "count", false))
        metrics.add(MetricValue(
            "attribution.consistent.ratio",
            if (none) null else reports.count { it.attributionConsistent }.toDouble() / reports.size,
            "ratio", true,
            if (none) emptyNote else "fraction of images whose replayed merge byte-matched the production merge (must be 1.0)",
        ))

        // ── Per-strategy: contribution %, latency (avg/p50/p95/max), contribution-per-ms ──
        for (strategy in OcrStrategy.MERGE_ORDER) {
            val perImage = reports.mapNotNull { r -> r.strategies.firstOrNull { it.strategy == strategy } }
            if (perImage.isEmpty()) {
                metrics.add(MetricValue("${strategy.label}.contribution.avg", null, "%", true,
                    if (none) emptyNote else "strategy not present in any evaluated image"))
                continue
            }
            val contrib = perImage.map { it.contributionPct }
            val latencies = perImage.map { it.elapsedMs.toLong() }.sorted()
            val avgContrib = contrib.average()
            val avgLatency = BenchmarkMath.mean(latencies) ?: 0.0
            metrics.add(MetricValue("${strategy.label}.contribution.avg", avgContrib, "%", true))
            metrics.add(MetricValue("${strategy.label}.survived.avg", perImage.map { it.survivedLines.toDouble() }.average(), "lines", true))
            metrics.add(MetricValue("${strategy.label}.duplicate.avg", perImage.map { it.duplicateLines.toDouble() }.average(), "lines", false))
            metrics.add(MetricValue("${strategy.label}.latency.avg", avgLatency, "ms", false))
            metrics.add(MetricValue("${strategy.label}.latency.p50", BenchmarkMath.percentile(latencies, 0.50), "ms", false))
            metrics.add(MetricValue("${strategy.label}.latency.p95", BenchmarkMath.percentile(latencies, 0.95), "ms", false))
            metrics.add(MetricValue("${strategy.label}.latency.max", latencies.last().toDouble(), "ms", false))
            metrics.add(MetricValue("${strategy.label}.contribution.per.ms",
                if (avgLatency > 0) avgContrib / avgLatency else 0.0, "%/ms", true,
                "value/cost ratio — higher is more valuable per millisecond spent"))
        }

        // ── Task 4: preprocessing cost, individually measured ──
        for (kind in listOf("grayscale", "invert", "upscale")) {
            val vals = reports.mapNotNull { it.preprocessingMs[kind] }
            metrics.add(MetricValue("preprocess.$kind.avg",
                if (vals.isEmpty()) null else vals.average(), "ms", false,
                if (vals.isEmpty()) emptyNote else "per-image accumulated $kind conversion time"))
        }

        // ── Sprint E4: input-size distribution + latency-vs-size correlation ──
        // Only reports that recorded geometry contribute (inputWidth > 0); nothing is inferred.
        for (kind in listOf("mlkit", "tesseract")) {
            val vals = reports.mapNotNull { it.engineMs[kind] }
            metrics.add(MetricValue("engine.$kind.avg",
                if (vals.isEmpty()) null else vals.average(), "ms", false,
                if (vals.isEmpty()) emptyNote else "per-image accumulated $kind engine time"))
        }

        val sized = reports.filter { it.inputWidth > 0 }
        val mps = sized.map { it.megapixels }.sorted()
        fun mpPctl(p: Double): Double? =
            if (mps.isEmpty()) null else mps[((mps.size - 1) * p).toInt().coerceIn(0, mps.size - 1)]
        val mpNote = if (sized.isEmpty()) "no reports recorded input geometry" else "over ${sized.size} report(s)"
        metrics.add(MetricValue("input.megapixels.p50", mpPctl(0.50), "MP", false, mpNote))
        metrics.add(MetricValue("input.megapixels.p95", mpPctl(0.95), "MP", false, mpNote))
        metrics.add(MetricValue("input.megapixels.max", mps.lastOrNull(), "MP", false, mpNote))
        for ((label, range) in listOf(
            "lt1mp" to (0.0..1.0), "1to4mp" to (1.0..4.0), "4to8mp" to (4.0..8.0), "gte8mp" to (8.0..Double.MAX_VALUE),
        )) {
            val bucket = sized.filter { it.megapixels >= range.start && it.megapixels < range.endInclusive && it.totalMs > 0 }
            metrics.add(MetricValue("ocr.latency.by.size.$label.avg",
                if (bucket.isEmpty()) null else bucket.map { it.totalMs }.average(), "ms", false,
                if (bucket.isEmpty()) "no sized reports in this bucket" else "whole-OCR wall time, n=${bucket.size}"))
        }
        val mergeVals = reports.map { it.mergeMs }.filter { it > 0 }
        metrics.add(MetricValue("merge.avg",
            if (mergeVals.isEmpty()) null else mergeVals.average(), "ms", false,
            if (mergeVals.isEmpty()) "no ensemble merges recorded" else "ensemble line-merge, n=${mergeVals.size}"))
        val totalLatency = reports.map { it.totalMs }.filter { it > 0 }.sorted()
        fun latencyPctl(p: Double): Double? =
            if (totalLatency.isEmpty()) null else totalLatency[((totalLatency.size - 1) * p).toInt().coerceIn(0, totalLatency.size - 1)]
        val latencyNote = if (totalLatency.isEmpty()) emptyNote else "whole-OCR wall time, n=${totalLatency.size}"
        metrics.add(MetricValue("ocr.total.latency.p50", latencyPctl(0.50), "ms", false, latencyNote))
        metrics.add(MetricValue("ocr.total.latency.p95", latencyPctl(0.95), "ms", false, latencyNote))
        metrics.add(MetricValue("ocr.total.latency.p99", latencyPctl(0.99), "ms", false, latencyNote))
        metrics.add(MetricValue("ocr.total.latency.max", totalLatency.lastOrNull(), "ms", false, latencyNote))
        return metrics
    }

    fun rows(reports: List<OcrImageReport>): List<Map<String, String>> =
        reports.flatMap { r ->
            val pageRow = mapOf(
                "kind" to "ocrPage",
                "imageId" to r.imageId,
                "inputWidth" to r.inputWidth.toString(),
                "inputHeight" to r.inputHeight.toString(),
                "megapixels" to "%.3f".format(Locale.US, r.megapixels),
                "grayscaleMs" to "%.1f".format(Locale.US, r.grayscaleMs),
                "upscaleMs" to "%.1f".format(Locale.US, r.upscaleMs),
                "invertMs" to "%.1f".format(Locale.US, r.invertMs),
                "mlKitMs" to "%.1f".format(Locale.US, r.mlKitMs),
                "tesseractMs" to "%.1f".format(Locale.US, r.tesseractMs),
                "mergeMs" to "%.1f".format(Locale.US, r.mergeMs),
                "totalOcrMs" to "%.0f".format(Locale.US, r.totalMs),
                "mergedChars" to r.mergedChars.toString(),
                "mergedLines" to r.mergedLineCount.toString(),
                "attributionConsistent" to r.attributionConsistent.toString(),
            )
            listOf(pageRow) + r.strategies.map { s ->
                mapOf(
                    "kind" to "strategy",
                    "imageId" to r.imageId,
                    "input" to if (r.inputWidth > 0) "${r.inputWidth}x${r.inputHeight} (%.1fMP)".format(Locale.US, r.megapixels) else "—",
                    "ocrTotalMs" to "%.0f".format(Locale.US, r.totalMs),
                    "strategy" to s.strategy.label,
                    "contributionPct" to "%.2f".format(Locale.US, s.contributionPct),
                    "survivedLines" to s.survivedLines.toString(),
                    "totalLines" to s.totalLines.toString(),
                    "duplicateLines" to s.duplicateLines.toString(),
                    "discardedLines" to s.discardedLines.toString(),
                    "chars" to s.chars.toString(),
                    "words" to s.words.toString(),
                    "latencyMs" to "%.1f".format(Locale.US, s.elapsedMs),
                    "attributionConsistent" to r.attributionConsistent.toString(),
                )
            }
        }
}
