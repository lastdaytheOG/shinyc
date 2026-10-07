package com.amar.vault.indexing

import com.amar.vault.planning.PlannerShadowRegistry
import com.amar.vault.VaultLog
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Sprint E1 — OCR strategy contribution instrumentation.
 *
 * PURE OBSERVATION. Nothing here changes OCR output, ordering, threading, or the merge
 * result. It records, per strategy, the text/time each produced, then REPLAYS the exact
 * [ImageContentExtractor.mergeAllOcrResults] algorithm to attribute which merged lines
 * survived from which strategy — and self-checks that its replayed merge equals the
 * production merge (a mismatch is flagged in the report, never fed back into the pipeline).
 *
 * Two consumers, isolated:
 *  - Production Evaluation Mode (developer-only toggle) sets [enabled] = true so REAL indexing
 *    ([ImageContentExtractor.extract] / [ImageContentExtractor.extractPdfPageText]) publishes each
 *    image's report into the [snapshot] buffer, which the benchmark dashboard aggregates + clears.
 *  - The GoldenDataset benchmark does NOT touch [enabled] or this buffer — it calls
 *    [ImageContentExtractor.extractInstrumented], which always records and returns the report
 *    directly, so a synthetic run can never pollute or wipe captured production samples.
 *
 * Disabled by default ([enabled] = false) → [recorderOrNull] returns null → the extractor's
 * timing wrappers become pure pass-throughs (zero behaviour change, negligible overhead).
 */

/** The five ensemble strategies, in the exact order results are assembled in runEnsemble. */
enum class OcrStrategy(val label: String) {
    RAW("strategy1_raw"),
    GRAYSCALE("strategy2_grayscale"),
    INVERT("strategy3_invert"),
    UPSCALE("strategy4_upscale"),
    TESSERACT("strategy5_tesseract");

    companion object {
        /** MUST equal the order of the `results` list built in ImageContentExtractor.runEnsemble. */
        val MERGE_ORDER: List<OcrStrategy> = listOf(RAW, GRAYSCALE, INVERT, UPSCALE, TESSERACT)
    }
}

/** Per-strategy contribution for one image — every field computed exactly, none estimated. */
data class StrategyContribution(
    val strategy: OcrStrategy,
    val elapsedMs: Double,
    val chars: Int,
    val words: Int,
    /** Non-blank lines this strategy emitted (pre-merge). */
    val totalLines: Int,
    /** Lines that survived the merge attributed to this strategy. */
    val survivedLines: Int,
    /** Lines skipped as already-covered duplicates during the merge. */
    val duplicateLines: Int,
    /** Lines this strategy produced that did not survive the merge (== total − survived). */
    val discardedLines: Int,
    /** survivedLines / (total merged lines) × 100. */
    val contributionPct: Double,
)

/**
 * Sprint E2 — one recorded strategy run (raw text + wall time), exposed READ-ONLY so the
 * ablation benchmark can replay leave-one-out merges. Never consumed by production code.
 */
data class RecordedRun(val strategy: OcrStrategy, val text: String, val elapsedMs: Double)

/** One image's full OCR strategy report (Task 5 flow: timings → contribution → merge → final). */
data class OcrImageReport(
    val imageId: String,
    val mergedLineCount: Int,
    val mergedChars: Int,
    /** True iff the replayed merge byte-matches the production merge (attribution is trustworthy). */
    val attributionConsistent: Boolean,
    val strategies: List<StrategyContribution>,
    /** Individually measured preprocessing cost per kind: grayscale / invert / upscale (ms, accumulated). */
    val preprocessingMs: Map<String, Double>,
    /** Individually measured OCR engine time per engine: mlkit / tesseract (ms, accumulated). */
    val engineMs: Map<String, Double> = emptyMap(),
    // Sprint E4 — per-run geometry + end-to-end timings (all measured; 0 when not recorded).
    /** Source bitmap dimensions the ensemble ran on. */
    val inputWidth: Int = 0,
    val inputHeight: Int = 0,
    /** inputWidth × inputHeight / 1e6 — the latency-vs-size correlation axis. */
    val megapixels: Double = 0.0,
    /** Measured line-merge time of the ensemble (0 for tier-1-accepted PDF pages: no ensemble merge ran). */
    val mergeMs: Double = 0.0,
    /** Whole-OCR wall time for this input (ensemble or PDF ladder, including escalation). */
    val totalMs: Double = 0.0,
) {
    val grayscaleMs: Double get() = preprocessingMs["grayscale"] ?: 0.0
    val upscaleMs: Double get() = preprocessingMs["upscale"] ?: 0.0
    val invertMs: Double get() = preprocessingMs["invert"] ?: 0.0
    val mlKitMs: Double get() = engineMs["mlkit"] ?: 0.0
    val tesseractMs: Double get() = engineMs["tesseract"] ?: 0.0
}

/**
 * Per-call recorder (one instance per [ImageContentExtractor.extract] invocation — created only
 * when instrumentation is enabled, so it is inherently concurrency-safe: no cross-image mixing).
 */
class OcrStrategyRecorder {

    private class Run(val text: String, val elapsedMs: Double)
    private val runs = ConcurrentHashMap<OcrStrategy, Run>()
    private val preMs = ConcurrentHashMap<String, Double>()
    private val engineMs = ConcurrentHashMap<String, Double>()

    /** Called by each strategy's timing wrapper with its produced text and wall time. */
    fun record(strategy: OcrStrategy, text: String, elapsedMs: Double) {
        runs[strategy] = Run(text, elapsedMs)
    }

    /** Called by each preprocessing timing wrapper (grayscale / invert / upscale). Accumulates. */
    fun pre(kind: String, ms: Double) {
        preMs.merge(kind, ms) { a, b -> a + b }
    }

    /** Called by engine wrappers. Accumulates true OCR engine time, excluding preprocessing. */
    fun engine(kind: String, ms: Double) {
        engineMs.merge(kind, ms) { a, b -> a + b }
    }

    /**
     * Sprint E2 — read-only snapshot of the recorded runs in [OcrStrategy.MERGE_ORDER].
     * Consumed only by the OCR ablation benchmark (leave-one-out merge replay); pure
     * observation, nothing in production reads it.
     */
    fun runsSnapshot(): List<RecordedRun> =
        OcrStrategy.MERGE_ORDER.mapNotNull { s -> runs[s]?.let { RecordedRun(s, it.text, it.elapsedMs) } }

    // Sprint E4 — per-run geometry + end-to-end timings (set from already-measured values).
    @Volatile private var inputW = 0
    @Volatile private var inputH = 0
    @Volatile private var mergeMs = 0.0
    @Volatile private var totalMs = 0.0

    fun setInput(width: Int, height: Int) {
        inputW = width
        inputH = height
    }

    fun setMergeMs(ms: Double) {
        mergeMs = ms
    }

    fun setTotalMs(ms: Double) {
        totalMs = ms
    }

    /**
     * Replay [ImageContentExtractor.mergeAllOcrResults] with provenance. The loop below is a
     * line-for-line mirror of the production merge (same trim, same lowercase+`\s+` normalize,
     * same "skip if an existing line contains this" test, same substring eviction). Because it
     * reproduces the algorithm exactly and in [OcrStrategy.MERGE_ORDER], its merged output must
     * equal the production merge — asserted via [actualMerged] and surfaced as
     * [OcrImageReport.attributionConsistent].
     */
    fun buildReport(imageId: String, actualMerged: String, linedUp: Boolean = false): OcrImageReport {
        val ws = Regex("\\s+")
        val seen = HashSet<String>()
        val mergedLines = ArrayList<String>()
        val perStrategy = LinkedHashMap<OcrStrategy, MutableContribution>()

        // A picture's passes are lined up by [ReadingMerge], which says itself whose wording
        // each stored line is: a pass's surviving lines are those stored in its wording.
        if (linedUp) {
            val order = OcrStrategy.MERGE_ORDER
            val lines = ReadingMerge.lines(order.map { runs[it]?.text.orEmpty() })
            for ((at, strategy) in order.withIndex()) {
                val run = runs[strategy] ?: continue
                val own = run.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                perStrategy[strategy] = MutableContribution().apply {
                    elapsedMs = run.elapsedMs
                    chars = run.text.length
                    totalLines = own.size
                    words = own.sumOf { line -> line.split(ws).count { it.isNotEmpty() } }
                    survivedLines = lines.count { it.from == at }
                    duplicateLines = totalLines - survivedLines
                }
            }
            mergedLines += lines.map { it.text }
        } else

        for (strategy in OcrStrategy.MERGE_ORDER) {
            val run = runs[strategy] ?: continue
            val c = MutableContribution().apply {
                elapsedMs = run.elapsedMs
                chars = run.text.length
            }
            val text = run.text
            if (text.isNotBlank()) {
                for (line in text.split("\n")) {
                    val cleaned = line.trim()
                    if (cleaned.isEmpty()) continue
                    c.totalLines++
                    c.words += cleaned.split(ws).count { it.isNotEmpty() }
                    val normalized = cleaned.lowercase().replace(ws, " ")
                    if (seen.any { it.contains(normalized) }) { c.duplicateLines++; continue }
                    seen.removeAll { normalized.contains(it) }
                    seen.add(normalized)
                    mergedLines.add(cleaned)
                    c.survivedLines++
                }
            }
            perStrategy[strategy] = c
        }

        val mergedText = mergedLines.joinToString("\n")
        val consistent = mergedText == actualMerged
        if (!consistent) {
            VaultLog.w(
                "OcrInstrumentation",
                "attribution merge mismatch for '$imageId' (replay=${mergedText.length}ch vs actual=${actualMerged.length}ch) — contribution reported but flagged inconsistent",
            )
        }

        val totalSurvived = mergedLines.size
        val contributions = OcrStrategy.MERGE_ORDER.mapNotNull { s ->
            perStrategy[s]?.let { m ->
                StrategyContribution(
                    strategy = s,
                    elapsedMs = m.elapsedMs,
                    chars = m.chars,
                    words = m.words,
                    totalLines = m.totalLines,
                    survivedLines = m.survivedLines,
                    duplicateLines = m.duplicateLines,
                    discardedLines = m.totalLines - m.survivedLines,
                    contributionPct = if (totalSurvived > 0) 100.0 * m.survivedLines / totalSurvived else 0.0,
                )
            }
        }
        return OcrImageReport(
            imageId = imageId,
            mergedLineCount = totalSurvived,
            mergedChars = mergedText.length,
            attributionConsistent = consistent,
            strategies = contributions,
            preprocessingMs = preMs.toMap(),
            engineMs = engineMs.toMap(),
            inputWidth = inputW,
            inputHeight = inputH,
            megapixels = OcrPerfGuards.megapixels(inputW, inputH),
            mergeMs = mergeMs,
            totalMs = totalMs,
        )
    }

    private class MutableContribution {
        var elapsedMs = 0.0
        var chars = 0
        var words = 0
        var totalLines = 0
        var survivedLines = 0
        var duplicateLines = 0
    }
}

/** Process-wide toggle + a bounded buffer of recent reports (developer-only diagnostics). */
object OcrInstrumentation {

    /** Production Evaluation Mode toggle. When true, real indexing publishes reports to [snapshot]. */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            // One developer capture session must observe OCR, document profiling, and the
            // planner together. Planner shadow is observation-only; no worker reads a plan.
            PlannerShadowRegistry.setDefaultCaptureEnabled(value)
        }

    private const val MAX_REPORTS = 500
    private val reports = Collections.synchronizedList(ArrayList<OcrImageReport>())

    /** A fresh per-call recorder when enabled; null otherwise (→ zero-overhead pass-through). */
    fun recorderOrNull(): OcrStrategyRecorder? = if (enabled) OcrStrategyRecorder() else null

    fun publish(report: OcrImageReport) {
        synchronized(reports) {
            reports.add(report)
            while (reports.size > MAX_REPORTS) reports.removeAt(0)
        }
        VaultLog.d("OcrInstrumentation", format(report))
    }

    fun snapshot(): List<OcrImageReport> = synchronized(reports) { ArrayList(reports) }

    fun clear() {
        synchronized(reports) { reports.clear() }
        PlannerShadowRegistry.clear()
    }

    /** Human-readable per-image flow: timings → contribution → merge (developer log). */
    fun format(r: OcrImageReport): String = buildString {
        append("OCR[${r.imageId}] merged=${r.mergedLineCount}ln/${r.mergedChars}ch")
        if (r.inputWidth > 0) {
            append(" in=${r.inputWidth}x${r.inputHeight} (${"%.1f".format(r.megapixels)}MP)")
            append(" total=${"%.0f".format(r.totalMs)}ms merge=${"%.1f".format(r.mergeMs)}ms")
        }
        if (!r.attributionConsistent) append("  ⚠ ATTRIBUTION_MISMATCH")
        for (s in r.strategies) {
            append("\n  ${s.strategy.label}: ${"%.1f".format(s.contributionPct)}% ")
            append("(${s.survivedLines}/${s.totalLines} survived, ${s.duplicateLines} dup, ")
            append("${s.chars}ch/${s.words}w, ${"%.1f".format(s.elapsedMs)}ms)")
        }
        if (r.preprocessingMs.isNotEmpty()) {
            append("\n  preprocess: ")
            append(r.preprocessingMs.entries.joinToString(" ") { "${it.key}=${"%.1f".format(it.value)}ms" })
        }
        if (r.engineMs.isNotEmpty()) {
            append("\n  engines: ")
            append(r.engineMs.entries.joinToString(" ") { "${it.key}=${"%.1f".format(it.value)}ms" })
        }
    }
}
