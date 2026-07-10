package com.amar.vault.benchmark

import android.content.Context
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.indexing.OcrImageReport

/**
 * Sprint E1 — OCR Strategy Contribution Benchmark (GoldenDataset mode).
 *
 * Runs the REAL production OCR ensemble ([ImageContentExtractor.extractInstrumented] — the exact
 * stage the image indexing path uses, unmodified) over every golden case that supplies a media
 * file, computing per-strategy merge contribution EXACTLY (the recorder replays the production
 * merge and self-checks byte-equality). Aggregation is shared with Production Evaluation Mode via
 * [OcrContributionAggregator].
 *
 * [extractInstrumented] is used (not [ImageContentExtractor.extract]) so this synthetic run NEVER
 * writes into the production capture buffer — a GoldenDataset run and a live-indexing capture
 * session are fully isolated.
 *
 * No number is fabricated. When the golden dataset has no scorable OCR cases (e.g. only the
 * templates that ship in `starter.json`), every strategy metric is null with a note — the tables
 * populate only when a real corpus is present, or via Production Evaluation Mode.
 */
class OcrContributionBenchmark(
    private val context: Context,
    private val datasetStore: GoldenDatasetStore,
) {

    // Same class the production pipeline uses; instantiated here only to *call* it.
    private val extractor by lazy { ImageContentExtractor(context) }

    suspend fun run(cases: List<BenchmarkCase>): BenchmarkSection {
        val ocrCases = cases.filter { it.supportsOcr }
        val reports = mutableListOf<OcrImageReport>()
        val skippedRows = mutableListOf<Map<String, String>>()
        var skipped = 0

        for (case in ocrCases) {
            val bitmap = datasetStore.loadMediaBitmap(case)
            if (bitmap == null) {
                skipped++
                skippedRows.add(mapOf(
                    "imageId" to case.id, "status" to "SKIPPED",
                    "reason" to "media file missing/undecodable: ${case.mediaFile}",
                ))
                continue
            }
            val report = try {
                extractor.extractInstrumented(bitmap, sourceId = case.id).second
            } finally {
                bitmap.recycle()
            }
            if (report == null) {
                skipped++
                skippedRows.add(mapOf(
                    "imageId" to case.id, "status" to "SKIPPED",
                    "reason" to "no OCR report produced (blank OCR?)",
                ))
                continue
            }
            reports.add(report)
        }

        val emptyNote =
            "no OCR cases with media in the golden dataset — templates are skipped; push a real corpus " +
                "(assets or filesDir/benchmark/GoldenDataset), or use Production Evaluation Mode to capture real indexing"
        val section = OcrContributionAggregator.section(
            reports, skipped,
            id = "ocr.contribution",
            title = "OCR strategy contribution & latency — GoldenDataset (exact merge attribution)",
            emptyNote = emptyNote,
        )
        // Surface skipped cases alongside the per-strategy rows.
        return section.copy(rows = skippedRows + section.rows)
    }
}
