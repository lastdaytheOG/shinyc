package com.amar.vault.benchmark

import com.amar.vault.planning.OperatorId
import com.amar.vault.planning.PlannerShadowRecord

/**
 * Planner shadow report. It compares planned operator shape with the known legacy entry-point
 * operator set. Operator counts are explicitly a proxy: this module never fabricates latency,
 * CPU, energy, or quality savings from counts.
 */
object PlannerShadowBenchmark {
    fun section(records: List<PlannerShadowRecord>): BenchmarkSection {
        val note = if (records.isEmpty()) {
            "no shadow observations captured; enable PlannerShadowRegistry during a real indexing run"
        } else {
            "operator counts are a derived work-shape proxy, not measured latency or energy"
        }
        val decisions = records.size
        val planned = records.count { it.plan != null }
        val refused = decisions - planned
        val legacyOperators = records.sumOf { it.observedOperators.size }
        val plannedOperators = records.sumOf { it.plan?.nodes?.size ?: 0 }
        val reduction = if (legacyOperators > 0) {
            100.0 * (legacyOperators - plannedOperators) / legacyOperators
        } else null
        val legacyOcr = records.sumOf { record ->
            record.observedOperators.count { it.startsWith("legacy.ocr.") }
        }
        val plannedOcr = records.sumOf { record ->
            record.plan?.nodes?.count {
                it.id == OperatorId.OCR_LIGHT || it.id == OperatorId.OCR_ESCALATE
            } ?: 0
        }
        val deterministic = if (decisions > 0) {
            100.0 * records.count { it.decision.deterministic } / decisions
        } else null

        val metrics = listOf(
            MetricValue("shadow.decisions", decisions.toDouble(), "count", true, note),
            MetricValue("shadow.planned", planned.toDouble(), "count", true, note),
            MetricValue("shadow.refused", refused.toDouble(), "count", false, note),
            MetricValue("shadow.deterministic.pct", deterministic, "%", true, note),
            MetricValue("shadow.legacy.operatorCount", legacyOperators.toDouble(), "operator-count", false, note),
            MetricValue("shadow.planned.operatorCount", plannedOperators.toDouble(), "operator-count", false, note),
            MetricValue(
                "shadow.operatorCountReduction.pct",
                reduction,
                "%",
                true,
                "derived planned-vs-legacy operator-count proxy; not a speedup claim",
            ),
            MetricValue("shadow.legacy.ocrPasses", legacyOcr.toDouble(), "operator-count", false, note),
            MetricValue("shadow.planned.ocrPasses", plannedOcr.toDouble(), "operator-count", false, note),
            MetricValue(
                "shadow.semanticDeferred",
                records.count { it.plan?.semanticCoverage?.name == "DEFERRED" }.toDouble(),
                "count", false, note,
            ),
        )

        val rows = records.map { record ->
            mapOf(
                "decisionId" to record.decision.decisionId,
                "sourceClass" to record.sourceClass.name,
                "phase" to record.phase,
                "selected" to (record.plan?.family?.name ?: "REFUSED"),
                "legacyOperatorCount" to record.observedOperators.size.toString(),
                "plannedOperatorCount" to (record.plan?.nodes?.size ?: 0).toString(),
                "legacyOcrPasses" to record.observedOperators.count { it.startsWith("legacy.ocr.") }.toString(),
                "plannedOcrPasses" to (record.plan?.nodes?.count {
                    it.id == OperatorId.OCR_LIGHT || it.id == OperatorId.OCR_ESCALATE
                } ?: 0).toString(),
                "semanticCoverage" to (record.plan?.semanticCoverage?.name ?: "NONE"),
            )
        }
        return BenchmarkSection(
            id = "planner.shadow",
            title = "Planner shadow (planned shape vs legacy work proxy)",
            metrics = metrics,
            rows = rows,
        )
    }
}
