package com.amar.vault.benchmark

import android.content.Context
import com.amar.vault.indexing.ImageContentExtractor

/**
 * Module 4 — OCR accuracy & latency.
 *
 * Runs the REAL production OCR path over every golden case that supplies a media file +
 * hand-verified ground truth, and scores:
 *
 *  - character accuracy = 1 − charLevenshtein / max(len)      (whitespace-normalized, lowercased)
 *  - word accuracy      = 1 − wordLevenshtein / max(words)    (lowercased tokens)
 *  - **CER**            = charLevenshtein / len(truth)        (case-sensitive)
 *  - **WER**            = wordLevenshtein / words(truth)      (case-sensitive)
 *  - latency            = wall-clock per image
 *
 * OCR confidence is NOT reported: the production extractor merges five engine passes
 * and does not expose per-pass confidences. The metric exists in the report with a
 * null value so the gap is visible instead of fabricated.
 *
 * Before/After comparison comes from the regression runner over stored reports —
 * this module only measures the "now".
 *
 * ## Why CER exists alongside character accuracy
 *
 * They are not interchangeable. `charAccuracy` divides by `max(len(ocr), len(truth))`; CER
 * divides by `len(truth)`, which is the standard OCR definition and the unit the NPU-OCR
 * acceptance gates are written in (see `docs/NPU_OCR_ASSET_GUIDE.md` §7 — every gate is stated
 * in "CER points"). The denominators diverge exactly when the engine over- or under-produces
 * text, which is the failure mode that matters most. Both are reported: `charAccuracy` keeps
 * pinned baselines comparable, CER is what the gates read.
 *
 * ## Two OCR entry points, never conflated
 *
 * `PDF` cases route through [ImageContentExtractor.extractPdfPageText] — the per-page path the
 * document pipeline actually uses, and the one a future NPU engine would replace. Everything
 * else routes through [ImageContentExtractor.extract], the image/screenshot path. These are
 * different ladders with different costs, so they are scored separately (`cer.pdfPage` vs
 * `cer.image`) as well as pooled. Before this split the module measured only the image path,
 * which meant the PDF-page path — the actual OCR bottleneck in document import — was never
 * scored at all.
 *
 * ## Unicode normalization
 *
 * Both sides are NFC-normalized before comparison. The production OCR path already NFC-normalizes
 * its output, so a human-typed ground truth stored as NFD would differ from byte-identical OCR
 * text purely by composition form — scoring Devanagari far below its true accuracy for a reason
 * invisible in the report. This changes previously-recorded values only for cases whose ground
 * truth was not already NFC; where it changes them, the earlier number was wrong.
 */
class OcrBenchmark(
    private val context: Context,
    private val datasetStore: GoldenDatasetStore,
) {

    // Same class the production pipeline uses; instantiated here only to *call* it.
    private val extractor by lazy { ImageContentExtractor(context) }

    private companion object {
        const val PATH_PDF_PAGE = "pdfPage"
        const val PATH_IMAGE = "image"
    }

    /** Per-case scoring outcome, kept so aggregates can be sliced without rescoring. */
    private data class Scored(
        val charAccuracy: Double,
        val wordAccuracy: Double,
        val cer: Double?,
        val wer: Double?,
        val script: String,
        val stratum: String,
        val path: String,
        val contentType: BenchmarkContentType,
    )

    suspend fun run(cases: List<BenchmarkCase>): BenchmarkSection {
        val ocrCases = cases.filter { it.supportsOcr }
        val rows = mutableListOf<Map<String, String>>()
        val scored = mutableListOf<Scored>()
        val latencies = mutableListOf<Long>()
        var skipped = 0

        for (case in ocrCases) {
            val bitmap = datasetStore.loadMediaBitmap(case)
            if (bitmap == null) {
                skipped++
                val media = datasetStore.checkMedia(case)
                rows.add(mapOf(
                    "caseId" to case.id,
                    "status" to "SKIPPED",
                    "code" to media.code,
                    "reason" to media.detail,
                ))
                continue
            }

            // The PDF-page ladder and the image ladder are different production code paths.
            // Route by content type so each case is scored against the path it represents.
            val usePdfPagePath = case.contentType == BenchmarkContentType.PDF
            val t0 = System.currentTimeMillis()
            val ocrText = try {
                if (usePdfPagePath) {
                    // renderHiRes = null: the golden media is a fixed bitmap, so there is no
                    // higher-resolution re-render available. Strategy 4 falls back to its
                    // upscale path exactly as it does in production when a page cannot be
                    // re-rendered — measured, not simulated.
                    extractor.extractPdfPageText(bitmap, renderHiRes = null, sourceId = case.id)
                } else {
                    extractor.extract(bitmap, sourceId = case.id).ocrText
                }
            } finally {
                bitmap.recycle()
            }
            val elapsed = System.currentTimeMillis() - t0
            latencies.add(elapsed)

            val (script, scriptSource) = OcrScript.resolve(case)
            val stratum = OcrStratum.resolve(case)
            val path = if (usePdfPagePath) PATH_PDF_PAGE else PATH_IMAGE

            val ca = OcrScoringMath.characterAccuracy(ocrText, case.groundTruthText)
            val wa = OcrScoringMath.wordAccuracy(ocrText, case.groundTruthText)
            val cer = OcrScoringMath.characterErrorRate(ocrText, case.groundTruthText)
            val wer = OcrScoringMath.wordErrorRate(ocrText, case.groundTruthText)

            scored.add(Scored(ca, wa, cer, wer, script, stratum, path, case.contentType))
            rows.add(mapOf(
                "caseId" to case.id, "status" to "SCORED", "contentType" to case.contentType.name,
                "ocrPath" to path,
                "script" to script,
                "scriptSource" to scriptSource,
                "stratum" to stratum,
                "charAccuracy" to "%.4f".format(java.util.Locale.US, ca),
                "wordAccuracy" to "%.4f".format(java.util.Locale.US, wa),
                "cer" to (cer?.let { "%.4f".format(java.util.Locale.US, it) } ?: ""),
                "wer" to (wer?.let { "%.4f".format(java.util.Locale.US, it) } ?: ""),
                "latencyMs" to elapsed.toString(),
            ))
        }

        val none = scored.isEmpty()
        val noneNote = if (none) "no OCR cases with media + ground truth" else ""
        val sorted = latencies.sorted()

        val metrics = mutableListOf(
            MetricValue("charAccuracy.avg", scored.map { it.charAccuracy }.avgOrNull(), "ratio", true, noneNote),
            MetricValue("wordAccuracy.avg", scored.map { it.wordAccuracy }.avgOrNull(), "ratio", true, noneNote),
            // CER/WER are error rates: lower is better, and the regression runner reads that
            // direction from higherIsBetter. Getting this flag wrong would invert every verdict.
            MetricValue("cer.avg", scored.mapNotNull { it.cer }.avgOrNull(), "ratio", false, noneNote),
            MetricValue("wer.avg", scored.mapNotNull { it.wer }.avgOrNull(), "ratio", false, noneNote),
            MetricValue("latency.avg", BenchmarkMath.mean(latencies), "ms", false, noneNote),
            MetricValue("latency.p50", BenchmarkMath.percentile(sorted, 0.50), "ms", false, noneNote),
            MetricValue("latency.p95", BenchmarkMath.percentile(sorted, 0.95), "ms", false, noneNote),
            MetricValue("latency.p99", BenchmarkMath.percentile(sorted, 0.99), "ms", false, noneNote),
            MetricValue("latency.max", sorted.lastOrNull()?.toDouble(), "ms", false, noneNote),
            MetricValue("confidence.avg", null, "ratio", true,
                "not exposed by the production OCR ensemble (5-pass merge discards per-pass confidence)"),
            MetricValue("cases.scored", scored.size.toDouble(), "count", true),
            MetricValue("cases.skipped", skipped.toDouble(), "count", false),
        )

        // Per-script CER — the gate unit. These are emitted even when a script has zero cases,
        // as null + note, because "we never measured Hindi" is precisely the blind spot that an
        // omitted metric would hide: the aggregate would look healthy while carrying no Devanagari
        // signal at all.
        for (script in listOf(OcrScript.EN, OcrScript.HI, OcrScript.MIXED)) {
            val slice = scored.filter { it.script == script }
            metrics.add(MetricValue(
                "cer.$script",
                slice.mapNotNull { it.cer }.avgOrNull(),
                "ratio",
                false,
                if (slice.isEmpty()) "no golden cases tagged or derived as '$script'" else "",
            ))
            metrics.add(MetricValue("cases.$script", slice.size.toDouble(), "count", true))
        }

        // Per-stratum CER — "where does OCR break?". Canonical buckets are always emitted, as
        // null + note when empty, because an untested condition is itself the finding: an
        // aggregate CER computed entirely on clean pages looks healthy right up until a scanned
        // book is imported.
        val customStrata = scored.map { it.stratum }
            .filter { it != OcrStratum.UNTAGGED && it !in OcrStratum.CANONICAL }
            .distinct().sorted()
        for (stratum in OcrStratum.CANONICAL + customStrata) {
            val slice = scored.filter { it.stratum == stratum }
            metrics.add(MetricValue(
                "cer.$stratum",
                slice.mapNotNull { it.cer }.avgOrNull(),
                "ratio",
                false,
                if (slice.isEmpty()) "no golden cases tagged '$stratum' — this condition is unmeasured" else "",
            ))
            metrics.add(MetricValue("cases.$stratum", slice.size.toDouble(), "count", true))
        }
        // Untagged cases still score into the aggregate, so their count must be visible — a high
        // untagged count means the per-stratum breakdown covers only part of the set.
        val untagged = scored.count { it.stratum == OcrStratum.UNTAGGED }
        metrics.add(MetricValue(
            "cases.stratum.untagged", untagged.toDouble(), "count", false,
            if (untagged > 0) "cases with no 'stratum' tag — counted in aggregates, absent from the per-stratum split" else "",
        ))

        // Per-entry-point CER. The PDF-page path is the one NPU OCR targets; pooling it with the
        // image path would let a regression in either hide behind the other.
        for (path in listOf(PATH_PDF_PAGE, PATH_IMAGE)) {
            val slice = scored.filter { it.path == path }
            metrics.add(MetricValue(
                "cer.$path",
                slice.mapNotNull { it.cer }.avgOrNull(),
                "ratio",
                false,
                if (slice.isEmpty()) "no golden cases exercised the $path OCR entry point" else "",
            ))
            metrics.add(MetricValue("cases.$path", slice.size.toDouble(), "count", true))
        }

        // Per-content-type accuracy (screenshots / PDFs-as-images / photos / receipts).
        for ((type, values) in scored.groupBy { it.contentType }) {
            metrics.add(MetricValue(
                "charAccuracy.${type.name.lowercase()}",
                values.map { it.charAccuracy }.average(), "ratio", true,
            ))
        }

        return BenchmarkSection(
            id = "ocr",
            title = "OCR accuracy & latency (production ImageContentExtractor)",
            metrics = metrics,
            rows = rows,
        )
    }

    private fun List<Double>.avgOrNull(): Double? = if (isEmpty()) null else average()
}
