package com.amar.vault.benchmark

import com.amar.vault.retrieval.SearchRepository

/**
 * Validates the *runnability* of golden cases before a planner decision can rely on them.
 * Schema presence is not evidence: OCR needs a decodable input and retrieval needs the exact
 * expected parent document already present in the active index.
 */
class GoldenDatasetAudit(
    private val datasetStore: GoldenDatasetStore,
    private val repository: SearchRepository,
) {
    data class Result(
        val scorableRetrievalCases: Int,
        val scorableOcrCases: Int,
        val rows: List<Map<String, String>>,
    ) {
        fun toSection(): BenchmarkSection = BenchmarkSection(
            id = "golden.dataset.validity",
            title = "Golden dataset validity (runnable quality evidence)",
            metrics = listOf(
                MetricValue("retrieval.cases.scorable", scorableRetrievalCases.toDouble(), "count", true),
                MetricValue("ocr.cases.scorable", scorableOcrCases.toDouble(), "count", true),
                MetricValue("cases.blocked", rows.count { it["status"] == "BLOCKED" }.toDouble(), "count", false),
            ),
            rows = rows,
        )
    }

    suspend fun inspect(cases: List<BenchmarkCase>): Result {
        var scorableRetrieval = 0
        var scorableOcr = 0
        val rows = mutableListOf<Map<String, String>>()

        for (case in cases) {
            if (case.supportsOcr) {
                val template = placeholderField(case, ocr = true)
                val media = datasetStore.checkMedia(case)
                when {
                    template != null -> rows += blocked(case, "OCR", "TEMPLATE_FIELD", template)
                    !media.usable -> rows += blocked(case, "OCR", media.code, media.detail)
                    else -> {
                        scorableOcr++
                        rows += ready(case, "OCR", media.detail)
                    }
                }
            }
            if (case.supportsRetrieval) {
                val template = placeholderField(case, ocr = false)
                if (template != null) {
                    rows += blocked(case, "RETRIEVAL", "TEMPLATE_FIELD", template)
                    continue
                }
                val expected = (case.expectedResults + case.documentId).filter { it.isNotBlank() }.toSet()
                val present = repository.getByIds(expected.toList())
                    .map { it.parentDocumentId ?: it.id }.toSet()
                val missing = expected - present
                if (missing.isEmpty()) {
                    scorableRetrieval++
                    rows += ready(case, "RETRIEVAL", "all expected parent document ids are indexed")
                } else {
                    rows += blocked(
                        case, "RETRIEVAL", "EXPECTED_DOCUMENT_NOT_INDEXED",
                        "missing: ${missing.joinToString()}",
                    )
                }
            }
        }
        return Result(scorableRetrieval, scorableOcr, rows)
    }

    private fun placeholderField(case: BenchmarkCase, ocr: Boolean): String? {
        val fields = if (ocr) {
            listOf("groundTruthText" to case.groundTruthText, "mediaFile" to case.mediaFile)
        } else {
            listOf(
                "documentId" to case.documentId,
                "queries" to case.queries.joinToString(),
                "expectedResults" to case.expectedResults.joinToString(),
                "expectedRank" to case.expectedRank.joinToString(),
            )
        }
        return fields.firstOrNull { (_, value) -> value.contains("REPLACE", ignoreCase = true) }
            ?.let { (field, _) -> "$field contains an unresolved REPLACE marker" }
    }

    private fun ready(case: BenchmarkCase, modality: String, detail: String) = mapOf(
        "caseId" to case.id, "modality" to modality, "status" to "READY", "detail" to detail,
    )

    private fun blocked(case: BenchmarkCase, modality: String, code: String, detail: String) = mapOf(
        "caseId" to case.id, "modality" to modality, "status" to "BLOCKED", "code" to code, "detail" to detail,
    )
}
