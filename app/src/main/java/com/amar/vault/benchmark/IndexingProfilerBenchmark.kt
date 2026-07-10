package com.amar.vault.benchmark

import android.content.Context
import android.net.Uri
import com.amar.vault.IndexMetrics
import com.amar.vault.indexing.DocProfile
import com.amar.vault.indexing.DocProfileRecorder
import com.amar.vault.indexing.DocumentContentExtractor
import com.amar.vault.indexing.IndexingProfiler
import com.amar.vault.indexing.PageProfile
import com.amar.vault.indexing.ProfilerStage
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Sprint E3 — Slowest Document & Page profiler report (measurements only; NO optimizations).
 *
 * Ranks WHERE indexing time goes, from two honest sources:
 *  1. Production capture — [DocProfile]s recorded from REAL indexing while the developer
 *     capture toggle was on ([IndexingProfiler] buffer; read, never cleared here).
 *  2. Benchmark probe — every document file pushed to the golden-dataset media dir is run
 *     through the REAL production extraction stage ([DocumentContentExtractor.extract],
 *     unmodified) with a local recorder in the coroutine context. Read-only; nothing is
 *     indexed. Probe profiles cover extraction stages only (Room/BM25 not exercised) and
 *     are labeled `origin=benchmark-probe`.
 *
 * Outputs (all in one section, so ReportStore writes them automatically after every run):
 *  - Top 20 slowest documents, Top 50 slowest pages (rows)
 *  - Latency histograms (p50/p90/p95/p99/max): doc total, page OCR, strip, render
 *  - Outliers: page OCR > 3000ms, strip(share) > 300ms, render > 500ms, page > 5000ms,
 *    document > 30s — each with all measured stage timings
 *  - A five-question diagnosis (rows kind=diagnosis) answering where the time goes and the
 *    highest-ROI optimization class the measurements support.
 *
 * Measurement honesty: every number is a measured wall time or an explicitly-labeled derived
 * value (per-page strip is the doc strip total amortized over pages — production strips the
 * whole document in ONE PDFTextStripper pass, so a true per-page strip time does not exist).
 * Stage sums are compute time; the PDF pipeline overlaps strip/render with OCR, so sums may
 * exceed wall-clock totals. With no data every metric is null with a note.
 */

// ── Pure report math (host-testable: no Android, no I/O) ─────────────────────

object ProfilerReportMath {

    // Outlier thresholds (ms) — Sprint E3 spec.
    const val OUTLIER_PAGE_OCR_MS = 3_000.0
    const val OUTLIER_PAGE_STRIP_MS = 300.0
    const val OUTLIER_PAGE_RENDER_MS = 500.0
    const val OUTLIER_PAGE_TOTAL_MS = 5_000.0
    const val OUTLIER_DOC_TOTAL_MS = 30_000.0

    /** "extract minus instrumented PDF sub-stages" bucket for the stage-share decomposition. */
    const val STAGE_EXTRACT_OTHER = "extractOther"

    data class Pctl(val n: Int, val p50: Double, val p90: Double, val p95: Double, val p99: Double, val max: Double)

    /** Same index method as [BenchmarkMath.percentile], for Double samples. Null when empty. */
    fun percentiles(values: List<Double>): Pctl? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        fun p(f: Double) = s[((s.size - 1) * f).toInt().coerceIn(0, s.size - 1)]
        return Pctl(s.size, p(0.50), p(0.90), p(0.95), p(0.99), s.last())
    }

    data class PageRef(val doc: DocProfile, val page: PageProfile)

    fun allPages(profiles: List<DocProfile>): List<PageRef> =
        profiles.flatMap { d -> d.pages.map { PageRef(d, it) } }

    fun topDocuments(profiles: List<DocProfile>, n: Int): List<DocProfile> =
        profiles.sortedByDescending { it.totalMs }.take(n)

    fun topPages(profiles: List<DocProfile>, n: Int): List<PageRef> =
        allPages(profiles).sortedByDescending { it.page.pageTotalMs }.take(n)

    /** One flagged outlier. [page] == null for document-level outliers. */
    data class Outlier(
        val kind: String,
        val doc: DocProfile,
        val page: PageProfile?,
        val valueMs: Double,
        val thresholdMs: Double,
    )

    fun outliers(profiles: List<DocProfile>): List<Outlier> {
        val out = mutableListOf<Outlier>()
        for (d in profiles) {
            if (d.totalMs > OUTLIER_DOC_TOTAL_MS) {
                out.add(Outlier("docTotal>30s", d, null, d.totalMs, OUTLIER_DOC_TOTAL_MS))
            }
            for (p in d.pages) {
                if (p.ocrMs > OUTLIER_PAGE_OCR_MS) out.add(Outlier("ocr>3000ms", d, p, p.ocrMs, OUTLIER_PAGE_OCR_MS))
                if (p.stripShareMs > OUTLIER_PAGE_STRIP_MS) {
                    out.add(Outlier("stripShare>300ms", d, p, p.stripShareMs, OUTLIER_PAGE_STRIP_MS))
                }
                val render = p.renderMs + p.hiResRenderMs
                if (render > OUTLIER_PAGE_RENDER_MS) out.add(Outlier("render>500ms", d, p, render, OUTLIER_PAGE_RENDER_MS))
                if (p.pageTotalMs > OUTLIER_PAGE_TOTAL_MS) {
                    out.add(Outlier("pageTotal>5000ms", d, p, p.pageTotalMs, OUTLIER_PAGE_TOTAL_MS))
                }
            }
        }
        return out.sortedByDescending { it.valueMs }
    }

    /**
     * Disjoint stage decomposition summed across documents. PDF sub-stages are nested inside
     * `extract`, so `extract` itself is replaced by [STAGE_EXTRACT_OTHER] = max(0, extract −
     * instrumented sub-stages) to avoid double counting. Values are compute time (overlapped
     * stages can legitimately sum past wall-clock).
     */
    fun stageTotals(profiles: List<DocProfile>): Map<String, Double> {
        val nestedKeys = listOf(
            ProfilerStage.PDF_OPEN, ProfilerStage.PDF_LOAD, ProfilerStage.PDF_STRIP,
            ProfilerStage.PDF_NFC, ProfilerStage.PDF_TRUST, ProfilerStage.PDF_CHUNK,
            ProfilerStage.RENDER, ProfilerStage.PAGE_OCR,
        )
        val flatKeys = listOf(
            ProfilerStage.DEDUP, ProfilerStage.ROOM, ProfilerStage.BM25,
            ProfilerStage.EMBED, ProfilerStage.META, ProfilerStage.OCR_IMAGE,
        )
        val totals = LinkedHashMap<String, Double>()
        fun add(k: String, v: Double) {
            if (v > 0) totals.merge(k, v, Double::plus)
        }
        for (d in profiles) {
            var nested = 0.0
            for (k in nestedKeys) {
                val v = d.stage(k)
                add(k, v)
                nested += v
            }
            val extract = d.stage(ProfilerStage.EXTRACT)
            if (extract > 0) add(STAGE_EXTRACT_OTHER, max(0.0, extract - nested))
            for (k in flatKeys) add(k, d.stage(k))
        }
        return totals
    }

    data class Concentration(val topCount: Int, val outOf: Int, val topSharePct: Double)

    /** Share of the sum held by the top ⌈fraction⌉ of positive values (≥1 element). */
    fun concentration(values: List<Double>, topFraction: Double = 0.05): Concentration? {
        val pos = values.filter { it > 0 }.sortedDescending()
        if (pos.isEmpty()) return null
        val k = max(1, ceil(pos.size * topFraction).toInt())
        return Concentration(k, pos.size, pos.take(k).sum() * 100.0 / pos.sum())
    }

    data class Diagnosis(val question: String, val answer: String)

    private fun ms(v: Double) = "${v.toLong()}ms"
    private fun pct(v: Double) = "%.1f%%".format(Locale.US, v)

    /** The Sprint E3 deliverable: five measured answers. Never fabricates — empty ⇒ says so. */
    fun diagnosis(profiles: List<DocProfile>): List<Diagnosis> {
        if (profiles.isEmpty()) {
            return listOf(Diagnosis(
                "diagnosis",
                "no measured data — enable the capture toggle and index real files, or push documents " +
                    "to the golden-dataset media dir for the benchmark probe",
            ))
        }
        val out = mutableListOf<Diagnosis>()
        val grandTotal = profiles.sumOf { it.totalMs }

        // 1. Which documents dominate total indexing time?
        val byTotal = profiles.sortedByDescending { it.totalMs }
        val top = byTotal.take(3)
        val topShare = if (grandTotal > 0) top.sumOf { it.totalMs } * 100.0 / grandTotal else 0.0
        out.add(Diagnosis(
            "Which documents dominate total indexing time?",
            "top ${top.size} of ${profiles.size} document(s) = ${pct(topShare)} of ${ms(grandTotal)} total: " +
                top.joinToString("; ") { "${it.displayName} ${ms(it.totalMs)} (${it.pageCount}p, ${it.ocrFallbackPages} OCR)" },
        ))

        // 2. Which pages dominate OCR time?
        val ocrPages = allPages(profiles).filter { it.page.ocrMs > 0 }.sortedByDescending { it.page.ocrMs }
        val ocrTotal = ocrPages.sumOf { it.page.ocrMs }
        out.add(Diagnosis(
            "Which pages dominate OCR time?",
            if (ocrPages.isEmpty()) "no OCR-fallback pages measured"
            else {
                val top10 = ocrPages.take(10)
                val share = top10.sumOf { it.page.ocrMs } * 100.0 / ocrTotal
                "top ${top10.size} of ${ocrPages.size} OCR page(s) = ${pct(share)} of ${ms(ocrTotal)} OCR time; worst: " +
                    top10.take(3).joinToString("; ") { "${it.doc.displayName} p${it.page.page} ${ms(it.page.ocrMs)}" }
            },
        ))

        // 3. Pathological outliers or uniformly slow?
        val conc = concentration(allPages(profiles).map { it.page.pageTotalMs })
        val flagged = outliers(profiles)
        out.add(Diagnosis(
            "Pathological outliers or uniformly slow?",
            if (conc == null) "no per-page data measured (no PDF pages profiled)"
            else {
                val verdict = when {
                    conc.topSharePct >= 50 -> "PATHOLOGICAL OUTLIERS dominate"
                    conc.topSharePct >= 30 -> "mixed — a heavy tail plus a slow baseline"
                    else -> "UNIFORMLY slow — no dominant outliers"
                }
                "top ${conc.topCount} (≈5%) of ${conc.outOf} page(s) hold ${pct(conc.topSharePct)} of page compute time → " +
                    "$verdict; ${flagged.size} threshold outlier(s) flagged"
            },
        ))

        // 4. Which stage contributes the largest percentage of time?
        val totals = stageTotals(profiles)
        val attributed = totals.values.sum()
        val largest = totals.maxByOrNull { it.value }
        out.add(Diagnosis(
            "Which stage contributes the largest percentage of time?",
            if (largest == null || attributed <= 0) "no stage timings measured"
            else "'${largest.key}' = ${pct(largest.value * 100.0 / attributed)} of ${ms(attributed)} attributed stage time (" +
                totals.entries.sortedByDescending { it.value }.take(4)
                    .joinToString(", ") { "${it.key}=${pct(it.value * 100.0 / attributed)}" } +
                "); note: compute time — overlapped PDF stages can sum past wall clock",
        ))

        // 5. Highest-ROI optimization class SUPPORTED BY the measurements.
        val ocrConc = concentration(ocrPages.map { it.page.ocrMs })
        val roi = when {
            largest == null || attributed <= 0 -> "insufficient data for a recommendation"
            largest.key == ProfilerStage.PAGE_OCR || largest.key == ProfilerStage.OCR_IMAGE ->
                if (ocrConc != null && ocrConc.topSharePct >= 50)
                    "bound the few pathological OCR pages — top ${ocrConc.topCount} OCR page(s) already hold " +
                        "${pct(ocrConc.topSharePct)} of OCR time, so capping/short-circuiting them attacks most of the largest stage"
                else
                    "reduce uniform per-page OCR cost (OCR time is spread across pages, not concentrated — a per-page cap would not help)"
            largest.key == ProfilerStage.PDF_STRIP ->
                "reduce whole-document strip cost (see the Indexing module's probe.pdfStrip dedup/font breakdown for the safe lever)"
            largest.key == ProfilerStage.RENDER ->
                "reduce fallback-page render cost (dpi / bitmap size)"
            largest.key == ProfilerStage.EMBED -> "batch or defer embedding"
            largest.key == ProfilerStage.ROOM || largest.key == ProfilerStage.BM25 -> "batch index writes"
            else -> "investigate '${largest.key}' — it dominates but has no instrumented sub-breakdown yet"
        }
        out.add(Diagnosis(
            "Highest-ROI optimization class supported by the data?",
            "$roi. [Sprint E4 guard counters report whether the safe OCR bounds fired in this run.]",
        ))
        return out
    }
}

// ── Benchmark module ─────────────────────────────────────────────────────────

class IndexingProfilerBenchmark(private val context: Context) {

    companion object {
        const val TOP_DOCS = 20
        const val TOP_PAGES = 50
        private val PROBE_MIMES = mapOf(
            "pdf" to "application/pdf",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "epub" to "application/epub+zip",
        )
    }

    suspend fun run(): BenchmarkSection {
        val rows = mutableListOf<Map<String, String>>()
        val profiles = mutableListOf<DocProfile>()

        // 1. Production capture (read-only — the dashboard owns clearing the buffer).
        val captured = IndexingProfiler.snapshot()
        profiles += captured

        // 2. Benchmark probe: REAL production extraction over pushed documents, profiled via a
        //    local recorder (never the global buffer). Read-only; nothing is indexed/persisted.
        var probed = 0
        val mediaDir = File(context.filesDir, "${GoldenDatasetStore.DEVICE_DIR}/media")
        val docFiles = mediaDir.listFiles { f -> PROBE_MIMES.containsKey(f.extension.lowercase()) }
            .orEmpty().sortedBy { it.name }
        for (f in docFiles) {
            val mime = PROBE_MIMES.getValue(f.extension.lowercase())
            val rec = DocProfileRecorder(
                docId = f.toURI().toString(), displayName = f.name,
                fileType = f.extension.lowercase(), origin = "benchmark-probe",
            )
            val t0 = System.currentTimeMillis()
            try {
                withContext(rec) { DocumentContentExtractor().extract(context, Uri.fromFile(f), mime) }
                val ms = System.currentTimeMillis() - t0
                rec.stageMs(ProfilerStage.EXTRACT, ms)
                // Probe totals cover EXTRACTION ONLY (Room/BM25/embed are not exercised).
                profiles.add(rec.build(ms, "probe"))
                probed++
            } catch (e: Exception) {
                rows.add(mapOf("kind" to "skipped", "doc" to f.name, "reason" to "probe extraction failed: ${e.message}"))
            }
        }

        val emptyNote =
            "no profiles — enable the capture toggle (dev dashboard) and index real files, and/or push " +
                "PDF/DOCX/XLSX/EPUB files to filesDir/${GoldenDatasetStore.DEVICE_DIR}/media/ for the probe"
        val none = profiles.isEmpty()
        val metrics = mutableListOf<MetricValue>()
        metrics.add(MetricValue("docs.profiled", profiles.size.toDouble(), "count", true, if (none) emptyNote else ""))
        metrics.add(MetricValue("docs.captured.production", captured.size.toDouble(), "count", true))
        metrics.add(MetricValue("docs.probed.benchmark", probed.toDouble(), "count", true,
            "probe profiles cover extraction stages only (Room/BM25 not exercised)"))
        val pages = ProfilerReportMath.allPages(profiles)
        metrics.add(MetricValue("pages.profiled", pages.size.toDouble(), "count", true))

        // ── Histograms (p50/p90/p95/p99/max) ────────────────────────────────
        fun hist(name: String, values: List<Double>, note: String) {
            val p = ProfilerReportMath.percentiles(values)
            fun m(k: String, v: Double?) = metrics.add(MetricValue(
                "hist.$name.$k", v, "ms", false,
                if (p == null) (if (none) emptyNote else "no samples — $note") else "$note (n=${p.n})",
            ))
            m("p50", p?.p50); m("p90", p?.p90); m("p95", p?.p95); m("p99", p?.p99); m("max", p?.max)
        }
        hist("docTotal", profiles.map { it.totalMs }, "document total indexing (probe docs: extraction only)")
        hist("pageOcr", pages.filter { it.page.ocrUsed }.map { it.page.ocrMs }, "per fallback page, whole OCR ladder")
        hist("strip", profiles.map { it.stage(ProfilerStage.PDF_STRIP) }.filter { it > 0 },
            "per DOCUMENT — strip is one whole-doc pass, not measurable per page")
        hist("render", pages.map { it.page.renderMs + it.page.hiResRenderMs }.filter { it > 0 },
            "per fallback page (150dpi render + escalation re-render)")

        // ── Sprint E4: OCR outlier tiers + size/trust/type correlations ─────
        val ocrPages = pages.filter { it.page.ocrUsed && it.page.ocrMs > 0 }
        for ((label, threshold) in listOf("gt3s" to 3_000.0, "gt5s" to 5_000.0, "gt10s" to 10_000.0)) {
            metrics.add(MetricValue(
                "ocr.outliers.$label",
                if (none) null else ocrPages.count { it.page.ocrMs > threshold }.toDouble(),
                "count", false,
                if (none) emptyNote else "OCR-fallback pages over ${threshold.toLong()}ms (of ${ocrPages.size} OCR pages)",
            ))
        }
        val metricSnapshot = IndexMetrics.snapshot()
        fun counter(name: String): Double = (metricSnapshot[name] ?: 0L).toDouble()
        val timeoutCount = counter(IndexMetrics.Event.OCR_TESS_TIMEOUT)
        val downscaleCount = counter(IndexMetrics.Event.OCR_TESS_INPUT_DOWNSCALED)
        val upscaleBypassCount = counter(IndexMetrics.Event.OCR_UPSCALE_BYPASSED)
        val ocrUnits = ocrPages.size + profiles.count { it.stage(ProfilerStage.OCR_IMAGE) > 0 }
        val guardHits = timeoutCount + downscaleCount + upscaleBypassCount
        metrics.add(MetricValue("ocr.tesseract.timeout.count", timeoutCount, "count", false,
            "process-local IndexMetrics counter since app start"))
        metrics.add(MetricValue("ocr.optimization.tessInputDownscaled.count", downscaleCount, "count", false,
            "process-local IndexMetrics counter since app start"))
        metrics.add(MetricValue("ocr.optimization.upscaleBypassed.count", upscaleBypassCount, "count", false,
            "process-local IndexMetrics counter since app start"))
        metrics.add(MetricValue("ocr.optimization.hitRate",
            if (ocrUnits == 0) null else guardHits / ocrUnits, "hits/ocr-unit", false,
            if (ocrUnits == 0) "no profiled OCR units" else "guard hits divided by profiled OCR units (n=$ocrUnits); counters are process-local"))
        val pageMps = pages.filter { it.page.bitmapWidth > 0 }
            .map { com.amar.vault.indexing.OcrPerfGuards.megapixels(it.page.bitmapWidth, it.page.bitmapHeight) }
        val mpP = ProfilerReportMath.percentiles(pageMps)
        for ((k, v) in listOf("p50" to mpP?.p50, "p95" to mpP?.p95, "p99" to mpP?.p99, "max" to mpP?.max)) {
            metrics.add(MetricValue("ocr.bitmap.megapixels.$k", v, "MP", false,
                if (mpP == null) (if (none) emptyNote else "no rendered fallback pages") else "rendered fallback bitmaps (n=${mpP.n})"))
        }
        // Latency vs bitmap size (rendered fallback pages only — the ones with measured bitmaps).
        for ((label, lo, hi) in listOf(
            Triple("lt1mp", 0.0, 1.0), Triple("1to4mp", 1.0, 4.0),
            Triple("4to8mp", 4.0, 8.0), Triple("gte8mp", 8.0, Double.MAX_VALUE),
        )) {
            val bucket = ocrPages.filter {
                it.page.bitmapWidth > 0 &&
                    com.amar.vault.indexing.OcrPerfGuards.megapixels(it.page.bitmapWidth, it.page.bitmapHeight)
                        .let { mp -> mp >= lo && mp < hi }
            }
            metrics.add(MetricValue("ocr.latency.by.size.$label.avg",
                if (bucket.isEmpty()) null else bucket.map { it.page.ocrMs }.average(), "ms", false,
                if (bucket.isEmpty()) "no OCR pages in this size bucket" else "n=${bucket.size}"))
        }
        // Latency vs trust score (lower trust = noisier page image).
        for ((label, lo, hi) in listOf(
            Triple("trustLt0.1", 0.0, 0.1), Triple("trust0.1to0.3", 0.1, 0.3), Triple("trustGte0.3", 0.3, 2.0),
        )) {
            val bucket = ocrPages.filter { it.page.trustScore?.let { t -> t >= lo && t < hi } == true }
            metrics.add(MetricValue("ocr.latency.by.$label.avg",
                if (bucket.isEmpty()) null else bucket.map { it.page.ocrMs }.average(), "ms", false,
                if (bucket.isEmpty()) "no OCR pages in this trust bucket" else "n=${bucket.size}"))
        }
        // Latency by document type: per-fallback-page OCR for paged docs, whole-image
        // ensemble time for image-family docs (that is that path's OCR unit).
        for ((type, docs) in profiles.groupBy { it.fileType }) {
            val pageLat = docs.flatMap { d -> d.pages.filter { it.ocrUsed && it.ocrMs > 0 }.map { it.ocrMs } }
            val imageLat = docs.map { it.stage(ProfilerStage.OCR_IMAGE) }.filter { it > 0 }
            val unit = if (pageLat.isNotEmpty()) pageLat else imageLat
            metrics.add(MetricValue("ocr.latency.by.type.$type.avg",
                if (unit.isEmpty()) null else unit.average(), "ms", false,
                if (unit.isEmpty()) "no OCR units for this type"
                else if (pageLat.isNotEmpty()) "per fallback page, n=${unit.size}" else "whole-image ensemble, n=${unit.size}"))
        }

        // ── Stage share (disjoint decomposition; compute time) ──────────────
        val totals = ProfilerReportMath.stageTotals(profiles)
        val attributed = totals.values.sum()
        for ((k, v) in totals.entries.sortedByDescending { it.value }) {
            metrics.add(MetricValue("stage.share.$k", if (attributed > 0) v * 100.0 / attributed else null,
                "%", false, "${v.toLong()}ms summed across ${profiles.size} doc(s); compute time, stages overlap"))
        }

        // ── Top-20 documents / Top-50 pages / outliers / diagnosis (rows) ───
        val f1 = { v: Double -> "%.1f".format(Locale.US, v) }
        ProfilerReportMath.topDocuments(profiles, TOP_DOCS).forEachIndexed { i, d ->
            rows.add(mapOf(
                "kind" to "topDoc", "rank" to (i + 1).toString(),
                "doc" to d.displayName, "type" to d.fileType, "origin" to d.origin, "status" to d.status,
                "totalMs" to f1(d.totalMs),
                "pages" to d.pageCount.toString(),
                "trustedPages" to d.trustedPages.toString(),
                "ocrPages" to d.ocrFallbackPages.toString(),
                "avgTrust" to (d.avgTrustScore?.let { "%.2f".format(Locale.US, it) } ?: "—"),
                "extractMs" to f1(d.stage(ProfilerStage.EXTRACT)),
                "stripMs" to f1(d.stage(ProfilerStage.PDF_STRIP)),
                "renderMs" to f1(d.stage(ProfilerStage.RENDER)),
                "ocrMs" to f1(d.stage(ProfilerStage.PAGE_OCR) + d.stage(ProfilerStage.OCR_IMAGE)),
                "embedMs" to f1(d.stage(ProfilerStage.EMBED)),
                "roomMs" to f1(d.stage(ProfilerStage.ROOM)),
                "bm25Ms" to f1(d.stage(ProfilerStage.BM25)),
                "largestBitmap" to if (d.largestBitmapWidth > 0)
                    "${d.largestBitmapWidth}x${d.largestBitmapHeight} (${d.largestBitmapBytes / 1024}KiB)" else "—",
            ))
        }
        ProfilerReportMath.topPages(profiles, TOP_PAGES).forEachIndexed { i, ref ->
            val p = ref.page
            rows.add(mapOf(
                "kind" to "topPage", "rank" to (i + 1).toString(),
                "doc" to ref.doc.displayName, "page" to p.page.toString(),
                "pageTotalMs" to f1(p.pageTotalMs),
                "stripShareMs" to f1(p.stripShareMs) + " (amortized)",
                "renderMs" to f1(p.renderMs),
                "hiResRenderMs" to f1(p.hiResRenderMs),
                "ocrMs" to f1(p.ocrMs),
                "trust" to (p.trustScore?.let { "%.2f".format(Locale.US, it) } ?: "—"),
                "ocrUsed" to p.ocrUsed.toString(),
                "ocrTier1Accepted" to (p.ocrTier1Accepted?.toString() ?: "—"),
                "bitmap" to if (p.bitmapWidth > 0) "${p.bitmapWidth}x${p.bitmapHeight}" else "—",
            ))
        }
        val outs = ProfilerReportMath.outliers(profiles)
        metrics.add(MetricValue("outliers.flagged", outs.size.toDouble(), "count", false,
            if (none) emptyNote else "ocr>3000ms, stripShare>300ms, render>500ms, page>5000ms, doc>30s"))
        for (o in outs) {
            val p = o.page
            rows.add(mapOf(
                "kind" to "outlier", "outlier" to o.kind,
                // Sprint E4 — root-cause category from measured fields only.
                "category" to (p?.let {
                    OcrOutlierClassifier.classify(
                        megapixels = if (it.bitmapWidth > 0)
                            com.amar.vault.indexing.OcrPerfGuards.megapixels(it.bitmapWidth, it.bitmapHeight) else null,
                        ocrMs = it.ocrMs,
                        trustScore = it.trustScore,
                        mergedChars = null, // not recorded per profiler page — never guessed
                    )
                } ?: "document_aggregate"),
                "doc" to o.doc.displayName, "page" to (p?.page?.toString() ?: "—"),
                "valueMs" to f1(o.valueMs), "thresholdMs" to f1(o.thresholdMs),
                // All measured stage timings for the flagged unit:
                "stripShareMs" to (p?.let { f1(it.stripShareMs) + " (amortized)" } ?: f1(o.doc.stage(ProfilerStage.PDF_STRIP))),
                "nfcMs" to (p?.let { f1(it.nfcMs) } ?: f1(o.doc.stage(ProfilerStage.PDF_NFC))),
                "trustMs" to (p?.let { f1(it.trustMs) } ?: f1(o.doc.stage(ProfilerStage.PDF_TRUST))),
                "renderMs" to (p?.let { f1(it.renderMs + it.hiResRenderMs) } ?: f1(o.doc.stage(ProfilerStage.RENDER))),
                "ocrMs" to (p?.let { f1(it.ocrMs) }
                    ?: f1(o.doc.stage(ProfilerStage.PAGE_OCR) + o.doc.stage(ProfilerStage.OCR_IMAGE))),
                "docTotalMs" to f1(o.doc.totalMs),
                "trust" to (p?.trustScore?.let { "%.2f".format(Locale.US, it) } ?: "—"),
                "bitmap" to (p?.takeIf { it.bitmapWidth > 0 }?.let { "${it.bitmapWidth}x${it.bitmapHeight}" } ?: "—"),
            ))
        }
        for (d in ProfilerReportMath.diagnosis(profiles)) {
            rows.add(mapOf("kind" to "diagnosis", "question" to d.question, "answer" to d.answer))
        }

        return BenchmarkSection(
            id = "indexing.profiler",
            title = "Slowest documents & pages (Sprint E3 profiler — measurements only, no optimizations)",
            metrics = metrics,
            rows = rows,
        )
    }
}
