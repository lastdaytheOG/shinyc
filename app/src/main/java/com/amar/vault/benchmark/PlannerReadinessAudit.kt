package com.amar.vault.benchmark

import com.amar.vault.planning.PlannerShadowRecord

/**
 * Explicit release gate for planner-controlled execution. A green performance number is not
 * enough: the system needs scorable retrieval/OCR truth, captured plans, and a baseline.
 */
object PlannerReadinessAudit {
    fun section(
        dataset: GoldenDatasetAudit.Result,
        shadowRecords: List<PlannerShadowRecord>,
        baselineRunId: String?,
    ): BenchmarkSection {
        val retrievalCases = dataset.scorableRetrievalCases
        val ocrCases = dataset.scorableOcrCases
        val baselinePinned = baselineRunId != null
        val blockers = mutableListOf<Pair<String, String>>()
        if (retrievalCases == 0) blockers += "RETRIEVAL_GOLDEN_SET_MISSING" to
            "no scorable retrieval cases; cannot prove retrieval non-inferiority"
        if (ocrCases == 0) blockers += "OCR_GOLDEN_SET_MISSING" to
            "no OCR media + ground-truth cases; cannot measure OCR regression"
        if (shadowRecords.isEmpty()) blockers += "PLANNER_SHADOW_EMPTY" to
            "enable Capture ON, index real documents, then run the benchmark"
        if (!baselinePinned) blockers += "BASELINE_NOT_PINNED" to
            "pin a measured benchmark run before comparing planner changes"

        val ready = blockers.isEmpty()
        val note = if (ready) {
            "all evidence gates present; worker rollout still requires non-inferiority review"
        } else {
            "planner workers are blocked until every listed gate is resolved"
        }
        return BenchmarkSection(
            id = "planner.readiness",
            title = "Planner rollout readiness (quality and evidence gates)",
            metrics = listOf(
                MetricValue("readiness.workerRollout", if (ready) 1.0 else 0.0, "bool", true, note),
                MetricValue("readiness.retrievalGoldenCases", retrievalCases.toDouble(), "count", true, note),
                MetricValue("readiness.ocrGoldenCases", ocrCases.toDouble(), "count", true, note),
                MetricValue("readiness.shadowDecisions", shadowRecords.size.toDouble(), "count", true, note),
                MetricValue("readiness.baselinePinned", if (baselinePinned) 1.0 else 0.0, "bool", true,
                    "$note; baseline available at run start: ${baselineRunId ?: "none"}"),
                MetricValue("readiness.blockers", blockers.size.toDouble(), "count", false, note),
            ),
            rows = dataset.rows + blockers.map { (code, detail) ->
                mapOf("modality" to "PLANNER", "status" to "BLOCKED", "code" to code, "detail" to detail)
            },
        )
    }
}
