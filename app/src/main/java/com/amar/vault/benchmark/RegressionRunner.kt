package com.amar.vault.benchmark

import kotlin.math.abs

/**
 * Module 11 — Regression detection.
 *
 * Workflow: run a benchmark BEFORE a change → pin it (`ReportStore.promoteLatestToBaseline`)
 * → make the change → run again → [compare] the new report against the baseline.
 *
 * Rules:
 *  - only metrics present with a value in BOTH reports are compared (a metric that
 *    became unmeasurable is flagged NOT_COMPARABLE, never scored);
 *  - direction comes from the metric's own `higherIsBetter`;
 *  - a delta beyond [thresholdPercent] against the good direction ⇒ REGRESSED,
 *    beyond it in the good direction ⇒ IMPROVED, otherwise UNCHANGED;
 *  - purely informational counters (samples/counts with near-zero baseline) are
 *    compared on absolute delta to avoid divide-by-zero noise.
 *
 * Covers every section, which yields the required detectors for retrieval quality,
 * OCR accuracy, latency, memory, and storage — they are all just metrics here.
 */
class RegressionRunner(private val thresholdPercent: Double = 5.0) {

    fun compare(baseline: BenchmarkRunReport, current: BenchmarkRunReport): List<RegressionFinding> {
        val findings = mutableListOf<RegressionFinding>()
        for (section in current.sections) {
            val baseSection = baseline.sections.firstOrNull { it.id == section.id } ?: continue
            for (metric in section.metrics) {
                val baseMetric = baseSection.metrics.firstOrNull { it.name == metric.name } ?: continue
                findings.add(compareMetric(section.id, baseMetric, metric))
            }
        }
        return findings.sortedByDescending { it.verdict == RegressionVerdict.REGRESSED }
    }

    private fun compareMetric(sectionId: String, base: MetricValue, cur: MetricValue): RegressionFinding {
        val b = base.value
        val c = cur.value
        if (b == null || c == null) {
            return RegressionFinding(
                sectionId, cur.name, b, c, null, RegressionVerdict.NOT_COMPARABLE,
                if (b == null && c != null) "newly measurable (no baseline value)"
                else if (b != null) "became unmeasurable — investigate" else "unmeasured in both runs",
            )
        }
        if (abs(b) < 1e-9) {
            return RegressionFinding(
                sectionId, cur.name, b, c, null,
                if (abs(c - b) < 1e-9) RegressionVerdict.UNCHANGED else RegressionVerdict.NOT_COMPARABLE,
                if (abs(c - b) < 1e-9) "" else "baseline is zero — relative delta undefined (abs delta ${c - b})",
            )
        }
        val deltaPct = (c - b) / abs(b) * 100.0
        val goodDirection = if (cur.higherIsBetter) deltaPct > 0 else deltaPct < 0
        val verdict = when {
            abs(deltaPct) <= thresholdPercent -> RegressionVerdict.UNCHANGED
            goodDirection -> RegressionVerdict.IMPROVED
            else -> RegressionVerdict.REGRESSED
        }
        return RegressionFinding(sectionId, cur.name, b, c, deltaPct, verdict)
    }

    /** Render findings as a report section so regressions travel inside the report files. */
    fun toSection(baselineRunId: String?, findings: List<RegressionFinding>): BenchmarkSection {
        val regressed = findings.count { it.verdict == RegressionVerdict.REGRESSED }
        val improved = findings.count { it.verdict == RegressionVerdict.IMPROVED }
        val rows = findings
            .filter { it.verdict != RegressionVerdict.UNCHANGED }
            .map {
                mapOf(
                    "section" to it.sectionId,
                    "metric" to it.metricName,
                    "baseline" to (it.baseline?.toString() ?: "—"),
                    "current" to (it.current?.toString() ?: "—"),
                    "deltaPercent" to (it.deltaPercent?.let { d -> "%.2f".format(java.util.Locale.US, d) } ?: "—"),
                    "verdict" to it.verdict.name,
                    "note" to it.note,
                )
            }
        val note = if (baselineRunId == null) "no baseline pinned — run a benchmark, then 'Set as baseline' before making changes" else "vs baseline $baselineRunId, threshold ±$thresholdPercent%"
        val metrics = mutableListOf(
            MetricValue("regressed", if (baselineRunId == null) null else regressed.toDouble(), "count", false, note),
            MetricValue("improved", if (baselineRunId == null) null else improved.toDouble(), "count", true, note),
            MetricValue("compared", if (baselineRunId == null) null else findings.size.toDouble(), "count", true),
        )
        fun latencyImprovementMetric(sectionId: String, metricName: String) {
            val f = findings.firstOrNull { it.sectionId == sectionId && it.metricName == metricName }
            val improvement = f?.deltaPercent?.let { -it }
            metrics.add(MetricValue(
                "$sectionId.$metricName.improvementPct",
                if (baselineRunId == null) null else improvement,
                "%",
                true,
                if (baselineRunId == null) "no baseline pinned"
                else if (f?.deltaPercent == null) "not comparable"
                else "positive means current latency is lower than baseline",
            ))
        }
        for (metricName in listOf("latency.avg", "latency.p50", "latency.p95", "latency.p99", "latency.max")) {
            latencyImprovementMetric("ocr", metricName)
        }
        for (metricName in listOf(
            "ocr.total.latency.p50",
            "ocr.total.latency.p95",
            "ocr.total.latency.p99",
            "ocr.total.latency.max",
        )) {
            latencyImprovementMetric("ocr.contribution", metricName)
        }

        return BenchmarkSection(
            id = "regression",
            title = "Regression vs baseline",
            metrics = metrics,
            rows = rows,
        )
    }
}
