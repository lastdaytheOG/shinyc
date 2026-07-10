package com.amar.vault.benchmark

import android.content.Context
import com.amar.vault.UnicodeText
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.indexing.OcrStrategy
import com.amar.vault.indexing.OcrStrategyRecorder
import com.amar.vault.indexing.RecordedRun
import java.util.Locale

/**
 * Sprint E2 — OCR strategy ABLATION benchmark (leave-one-out) + escalation-ladder simulation.
 *
 * BENCHMARK-ONLY. Production OCR is not modified: each image is OCR'd exactly once through the
 * REAL production ensemble ([ImageContentExtractor.extractInstrumented] — same stage the image
 * indexing path uses), and every ablation below is a pure offline REPLAY of the production merge
 * over the per-strategy texts the recorder captured (Sprint E1 instrumentation). No strategy is
 * skipped, reordered, or re-run.
 *
 * Per image:
 *  1. Full 5-strategy merge = ground truth (self-checked byte-equal to the production output).
 *  2. Recompute the merge with exactly one strategy removed (leave-one-out), in production
 *     merge order.
 *  3. Measure what the full merge loses: surviving lines / characters / words not covered by
 *     the ablated merge, line- and char-level recall %, and latency saved (the removed
 *     strategy's own wall time = sequential compute saved; plus an approximate parallel
 *     wall-clock delta).
 *
 * Aggregation ranks strategies by recall loss per millisecond saved (lower = safer to drop),
 * and simulates the cumulative escalation ladder Raw → Tesseract → Grayscale → Upscale →
 * Invert, recommending a prefix ONLY if measured recall stays above the acceptance thresholds.
 * No number is fabricated — with no scorable OCR cases every metric is null with a note.
 */

// ── Pure replay math (host-testable: no Android, no I/O) ─────────────────────

object OcrAblationMath {

    /** Escalation ladder under evaluation: cheap / most-likely-sufficient first. */
    val LADDER: List<OcrStrategy> = listOf(
        OcrStrategy.RAW,
        OcrStrategy.TESSERACT,
        OcrStrategy.GRAYSCALE,
        OcrStrategy.UPSCALE,
        OcrStrategy.INVERT,
    )

    private val WS = Regex("\\s+")

    /**
     * Line-for-line mirror of `ImageContentExtractor.mergeAllOcrResults` (same trim, same
     * lowercase+whitespace normalize, same "skip if an existing line contains this" test, same
     * substring eviction), returning the surviving lines. Inputs MUST already be in production
     * merge order ([OcrStrategy.MERGE_ORDER]) for the replay to be exact.
     */
    fun mergeLines(texts: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        val unique = mutableListOf<String>()
        for (text in texts) {
            if (text.isBlank()) continue
            for (line in text.split("\n")) {
                val cleaned = line.trim()
                if (cleaned.isEmpty()) continue
                val normalized = cleaned.lowercase().replace(WS, " ")
                if (seen.any { it.contains(normalized) }) continue
                seen.removeAll { normalized.contains(it) }
                seen.add(normalized)
                unique.add(cleaned)
            }
        }
        return unique
    }

    /** One leave-one-out ablation for a single image. Every field measured, none estimated. */
    data class Ablation(
        val strategy: OcrStrategy,
        /** Full-merge surviving lines no longer covered when this strategy is removed. */
        val lostLines: Int,
        val lostChars: Int,
        val lostWords: Int,
        /** Covered full-merge lines / total full-merge lines × 100. */
        val lineRecallPct: Double,
        /** Covered full-merge chars / total full-merge chars × 100. */
        val charRecallPct: Double,
        /** The removed strategy's own wall time — compute saved in a sequential ladder. */
        val latencySavedMs: Double,
        /** max(all strategy times) − max(remaining) — approximate parallel wall-clock saved. */
        val wallSavedMs: Double,
    )

    /** Full ablation + ladder simulation for one image. */
    data class ImageAblation(
        val imageId: String,
        val fullLines: Int,
        /** Sum of full-merge line lengths (per-line accounting basis for char recall). */
        val fullChars: Int,
        /** Replayed full merge, joined — the caller self-checks it against the production output. */
        val fullMergedText: String,
        val ablations: List<Ablation>,
        /** Line recall % of each cumulative [LADDER] prefix (index 0 = Raw only). */
        val ladderRecallPct: List<Double>,
        /** Cumulative sequential compute of each prefix (sum of member strategy times). */
        val ladderCumMs: List<Double>,
    )

    /**
     * Evaluate one image from its recorded per-strategy runs. Returns null when the full merge
     * is empty (nothing to ablate — the caller reports the image as skipped, never as 100%).
     */
    fun evaluate(imageId: String, runs: List<RecordedRun>): ImageAblation? {
        if (runs.isEmpty()) return null
        val byStrategy = runs.associateBy { it.strategy }
        // Production merge order, restricted to the strategies that actually ran.
        val ordered = OcrStrategy.MERGE_ORDER.mapNotNull { byStrategy[it] }

        val fullLines = mergeLines(ordered.map { it.text })
        if (fullLines.isEmpty()) return null
        val fullNorm = fullLines.map { it.lowercase().replace(WS, " ") }
        val fullChars = fullLines.sumOf { it.length }
        val maxMs = ordered.maxOf { it.elapsedMs }

        val ablations = ordered.map { removed ->
            val ablated = mergeLines(ordered.filter { it.strategy != removed.strategy }.map { it.text })
            val ablatedNorm = ablated.map { it.lowercase().replace(WS, " ") }
            // A full-merge line survives the ablation if some ablated line still covers it
            // (same containment test the merge itself uses — removal can promote a longer
            // duplicate from another strategy, which is coverage, not loss).
            val lost = fullLines.indices.filter { i -> ablatedNorm.none { it.contains(fullNorm[i]) } }
            val lostChars = lost.sumOf { fullLines[it].length }
            val remainingMax = ordered.filter { it.strategy != removed.strategy }
                .maxOfOrNull { it.elapsedMs } ?: 0.0
            Ablation(
                strategy = removed.strategy,
                lostLines = lost.size,
                lostChars = lostChars,
                lostWords = lost.sumOf { fullLines[it].split(WS).count { w -> w.isNotEmpty() } },
                lineRecallPct = 100.0 * (fullLines.size - lost.size) / fullLines.size,
                charRecallPct = if (fullChars > 0) 100.0 * (fullChars - lostChars) / fullChars else 100.0,
                latencySavedMs = removed.elapsedMs,
                wallSavedMs = (maxMs - remainingMax).coerceAtLeast(0.0),
            )
        }

        // Cumulative ladder: merge each prefix (in production merge order) against the full merge.
        val ladderRecall = mutableListOf<Double>()
        val ladderCumMs = mutableListOf<Double>()
        for (k in 1..LADDER.size) {
            val members = LADDER.take(k).toSet()
            val prefix = ordered.filter { it.strategy in members }
            val prefixNorm = mergeLines(prefix.map { it.text }).map { it.lowercase().replace(WS, " ") }
            val covered = fullNorm.count { n -> prefixNorm.any { it.contains(n) } }
            ladderRecall.add(100.0 * covered / fullLines.size)
            ladderCumMs.add(prefix.sumOf { it.elapsedMs })
        }

        return ImageAblation(
            imageId = imageId,
            fullLines = fullLines.size,
            fullChars = fullChars,
            fullMergedText = fullLines.joinToString("\n"),
            ablations = ablations,
            ladderRecallPct = ladderRecall,
            ladderCumMs = ladderCumMs,
        )
    }
}

// ── Benchmark module ─────────────────────────────────────────────────────────

class OcrAblationBenchmark(
    private val context: Context,
    private val datasetStore: GoldenDatasetStore,
) {

    companion object {
        /**
         * Acceptance gate for recommending a ladder prefix: average line recall across images
         * must stay at/above this…
         */
        const val ACCEPTABLE_AVG_RECALL_PCT = 99.0

        /** …and no single image may fall below this floor. */
        const val ACCEPTABLE_MIN_RECALL_PCT = 95.0
    }

    // Same class the production pipeline uses; instantiated here only to *call* it.
    private val extractor by lazy { ImageContentExtractor(context) }

    suspend fun run(cases: List<BenchmarkCase>): BenchmarkSection {
        val ocrCases = cases.filter { it.supportsOcr }
        val images = mutableListOf<OcrAblationMath.ImageAblation>()
        val rows = mutableListOf<Map<String, String>>()
        var skipped = 0
        var consistentCount = 0

        for (case in ocrCases) {
            val bitmap = datasetStore.loadMediaBitmap(case)
            if (bitmap == null) {
                skipped++
                rows.add(mapOf(
                    "kind" to "skipped", "imageId" to case.id,
                    "reason" to "media file missing/undecodable: ${case.mediaFile}",
                ))
                continue
            }
            // ONE production ensemble run per image; the recorder captures each strategy's raw
            // text + latency for offline replay. extractInstrumented never touches the
            // production capture buffer, so this cannot pollute Production Evaluation Mode.
            val recorder = OcrStrategyRecorder()
            val content = try {
                extractor.extractInstrumented(bitmap, sourceId = case.id, recorder = recorder).first
            } finally {
                bitmap.recycle()
            }
            val ablation = OcrAblationMath.evaluate(case.id, recorder.runsSnapshot())
            if (ablation == null) {
                skipped++
                rows.add(mapOf(
                    "kind" to "skipped", "imageId" to case.id,
                    "reason" to "full ensemble merge is empty — nothing to ablate",
                ))
                continue
            }
            // Ground-truth self-check: the replayed full merge must byte-match the production
            // output (after the same NFC step the extractor applies). A mismatch invalidates
            // that image's attribution and is surfaced, never hidden.
            if (UnicodeText.nfc(ablation.fullMergedText) == content.ocrText) consistentCount++
            images.add(ablation)

            for (a in ablation.ablations) {
                rows.add(mapOf(
                    "kind" to "ablation",
                    "imageId" to ablation.imageId,
                    "removedStrategy" to a.strategy.label,
                    "lostLines" to a.lostLines.toString(),
                    "lostChars" to a.lostChars.toString(),
                    "lostWords" to a.lostWords.toString(),
                    "lineRecallPct" to "%.2f".format(Locale.US, a.lineRecallPct),
                    "charRecallPct" to "%.2f".format(Locale.US, a.charRecallPct),
                    "latencySavedMs" to "%.1f".format(Locale.US, a.latencySavedMs),
                    "wallSavedMs" to "%.1f".format(Locale.US, a.wallSavedMs),
                ))
            }
        }

        val emptyNote =
            "no OCR cases with media in the golden dataset — templates are skipped; push a real corpus " +
                "(assets or filesDir/benchmark/GoldenDataset) before trusting any ablation conclusion"
        val metrics = mutableListOf<MetricValue>()
        val none = images.isEmpty()
        metrics.add(MetricValue("images.evaluated", images.size.toDouble(), "count", true, if (none) emptyNote else ""))
        metrics.add(MetricValue("images.skipped", skipped.toDouble(), "count", false))
        metrics.add(MetricValue(
            "merge.selfcheck.ratio",
            if (none) null else consistentCount.toDouble() / images.size,
            "ratio", true,
            if (none) emptyNote else "fraction of images whose replayed full merge byte-matched production output (must be 1.0)",
        ))

        // ── Per-strategy ablation aggregates + ranking by recall loss per ms saved ──
        data class Agg(
            val strategy: OcrStrategy,
            val n: Int,
            val avgRecall: Double,
            val avgLostLines: Double,
            val avgLostChars: Double,
            val avgLostWords: Double,
            val avgSavedMs: Double,
            val avgWallSavedMs: Double,
            val lossPerMs: Double?,
        )
        val aggs = OcrStrategy.MERGE_ORDER.mapNotNull { strategy ->
            val per = images.mapNotNull { img -> img.ablations.firstOrNull { it.strategy == strategy } }
            if (per.isEmpty()) return@mapNotNull null
            val avgRecall = per.map { it.lineRecallPct }.average()
            val avgSaved = per.map { it.latencySavedMs }.average()
            Agg(
                strategy = strategy,
                n = per.size,
                avgRecall = avgRecall,
                avgLostLines = per.map { it.lostLines.toDouble() }.average(),
                avgLostChars = per.map { it.lostChars.toDouble() }.average(),
                avgLostWords = per.map { it.lostWords.toDouble() }.average(),
                avgSavedMs = avgSaved,
                avgWallSavedMs = per.map { it.wallSavedMs }.average(),
                lossPerMs = if (avgSaved > 0) (100.0 - avgRecall) / avgSaved else null,
            )
        }
        for (strategy in OcrStrategy.MERGE_ORDER) {
            val a = aggs.firstOrNull { it.strategy == strategy }
            val label = strategy.label
            if (a == null) {
                metrics.add(MetricValue("$label.ablation.recall.avg", null, "%", true,
                    if (none) emptyNote else "strategy not present in any evaluated image"))
                continue
            }
            metrics.add(MetricValue("$label.ablation.recall.avg", a.avgRecall, "%", true,
                "line recall of the 4-strategy merge without $label vs the full ensemble"))
            metrics.add(MetricValue("$label.ablation.lost.lines.avg", a.avgLostLines, "lines", false))
            metrics.add(MetricValue("$label.ablation.lost.chars.avg", a.avgLostChars, "chars", false))
            metrics.add(MetricValue("$label.ablation.lost.words.avg", a.avgLostWords, "words", false))
            metrics.add(MetricValue("$label.ablation.latency.saved.avg", a.avgSavedMs, "ms", true,
                "the strategy's own wall time — sequential compute saved by dropping it"))
            metrics.add(MetricValue("$label.ablation.wall.saved.avg", a.avgWallSavedMs, "ms", true,
                "approximate parallel wall-clock saved (assumes fully concurrent strategies)"))
            metrics.add(MetricValue("$label.ablation.recall.loss.per.ms", a.lossPerMs, "%/ms", false,
                if (a.lossPerMs == null) "strategy latency ~0ms — ratio undefined"
                else "recall %-points lost per ms of sequential compute saved — lower = safer to drop"))
        }
        // Ranking table (cheapest-to-drop first). Undefined ratios (0ms strategies) rank last.
        aggs.sortedWith(compareBy<Agg, Double?>(nullsLast()) { it.lossPerMs })
            .forEachIndexed { i, a ->
                rows.add(mapOf(
                    "kind" to "ranking",
                    "rank" to (i + 1).toString(),
                    "strategy" to a.strategy.label,
                    "avgLineRecallPct" to "%.2f".format(Locale.US, a.avgRecall),
                    "avgRecallLossPct" to "%.2f".format(Locale.US, 100.0 - a.avgRecall),
                    "avgLatencySavedMs" to "%.1f".format(Locale.US, a.avgSavedMs),
                    "recallLossPerMsSaved" to (a.lossPerMs?.let { "%.5f".format(Locale.US, it) } ?: "undefined"),
                    "images" to a.n.toString(),
                ))
            }

        // ── Escalation ladder Raw → Tesseract → Grayscale → Upscale → Invert ──
        var recommendedRungs: Int? = null
        for (k in 1..OcrAblationMath.LADDER.size) {
            val idx = k - 1
            val recalls = images.map { it.ladderRecallPct[idx] }
            val cums = images.map { it.ladderCumMs[idx] }
            val label = OcrAblationMath.LADDER.take(k).joinToString("+") { it.name.lowercase() }
            val avg = if (none) null else recalls.average()
            val min = if (none) null else recalls.min()
            metrics.add(MetricValue("ladder.rung$k.recall.avg", avg, "%", true,
                if (none) emptyNote else "cumulative ladder [$label] vs full ensemble"))
            metrics.add(MetricValue("ladder.rung$k.recall.min", min, "%", true,
                if (none) emptyNote else "worst single image at rung $k"))
            metrics.add(MetricValue("ladder.rung$k.latency.cum.avg",
                if (none) null else cums.average(), "ms", false,
                if (none) emptyNote else "sequential compute of the prefix (sum of member strategy times)"))
            if (!none) {
                rows.add(mapOf(
                    "kind" to "ladder",
                    "rung" to k.toString(),
                    "strategies" to label,
                    "avgRecallPct" to "%.2f".format(Locale.US, avg!!),
                    "minRecallPct" to "%.2f".format(Locale.US, min!!),
                    "avgCumLatencyMs" to "%.1f".format(Locale.US, cums.average()),
                ))
                if (recommendedRungs == null && k < OcrAblationMath.LADDER.size &&
                    avg >= ACCEPTABLE_AVG_RECALL_PCT && min >= ACCEPTABLE_MIN_RECALL_PCT
                ) {
                    recommendedRungs = k
                }
            }
        }
        metrics.add(MetricValue(
            "ladder.recommended.rungs", recommendedRungs?.toDouble(), "rungs", false,
            when {
                none -> "no measured data — no recommendation ($emptyNote)"
                recommendedRungs == null ->
                    "NO ladder prefix met avg≥$ACCEPTABLE_AVG_RECALL_PCT% + min≥$ACCEPTABLE_MIN_RECALL_PCT% line recall — keep the full 5-strategy ensemble"
                else ->
                    "shortest prefix [${OcrAblationMath.LADDER.take(recommendedRungs).joinToString("→") { it.name.lowercase() }}] " +
                        "meeting avg≥$ACCEPTABLE_AVG_RECALL_PCT% + min≥$ACCEPTABLE_MIN_RECALL_PCT% line recall over ${images.size} image(s) — " +
                        "validate on a larger corpus before acting; this benchmark changes nothing in production"
            },
        ))

        return BenchmarkSection(
            id = "ocr.ablation",
            title = "OCR strategy ablation (leave-one-out) & escalation-ladder simulation — GoldenDataset",
            metrics = metrics,
            rows = rows,
        )
    }
}
