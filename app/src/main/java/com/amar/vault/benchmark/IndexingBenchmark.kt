package com.amar.vault.benchmark

import android.content.Context
import android.net.Uri
import com.amar.vault.IndexMetrics
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import com.amar.vault.indexing.DocumentContentExtractor
import com.amar.vault.indexing.SentenceAwareChunker
import com.amar.vault.indexing.WordWindowChunker
import com.amar.vault.retrieval.Bm25Index
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.UUID

/**
 * Module 6 — Indexing stage benchmark.
 *
 * Two measurement strategies, both non-invasive:
 *
 * 1. **Observed production timings** — [IndexMetrics] already records OCR, metadata
 *    extraction, embedding and total-index durations from real indexing runs in this
 *    process. Those are surfaced as `observed.*` (avg per document + sample count).
 *    Stages the pipeline does not self-time (Room txn, BM25 add) show up here as
 *    unmeasured rather than derived.
 *
 * 2. **Controlled micro-probes** — stages that are safe to invoke directly are timed
 *    with a probe (`probe.*`):
 *      - chunking: both production chunkers over a fixed synthetic text (pure functions);
 *      - Room write: timed insert + delete of one namespaced `benchmark-probe-*` row
 *        through the production DAO (removed again in the same call);
 *      - BM25 add: one probe document of unique nonsense tokens (cannot match real
 *        queries; BM25 is in-memory and rebuilt from Room at launch, so the probe
 *        vanishes on restart);
 *      - extraction: timed [DocumentContentExtractor.extract] over any PDF pushed to
 *        `filesDir/benchmark/GoldenDataset/media/` (read-only; skipped when absent).
 *
 * Per-batch numbers are the observed per-document averages × counts accumulated by
 * the real workers — no synthetic batch is fabricated.
 */
class IndexingBenchmark(
    private val context: Context,
    private val db: VaultDatabase,
    private val bm25: Bm25Index,
) {

    companion object {
        private val PROBE_TEXT = buildString {
            repeat(120) { append("benchmark chunking probe sentence number $it keeps a realistic length. ") }
        }
        private const val PROBE_ROUNDS = 5
        /** Page window for the strip-breakdown diagnostic — keeps 3 re-strips cheap on large PDFs. */
        private const val SAMPLE_PAGES = 24
    }

    suspend fun run(): BenchmarkSection {
        val metrics = mutableListOf<MetricValue>()

        // ── 1. Observed production stage timings (real indexing runs, this process) ──
        val snap = IndexMetrics.snapshot()
        fun observed(metric: String, key: String, note: String = "") {
            val count = snap["$key.count"] ?: 0
            metrics.add(MetricValue(
                "observed.$metric.avg",
                if (count > 0) snap["$key.avg"]?.toDouble() else null,
                "ms/doc", false,
                if (count > 0) "over $count document(s) indexed this process" else "no documents indexed this process yet$note",
            ))
        }
        observed("ocr", IndexMetrics.Timing.OCR)
        observed("fastCommit", IndexMetrics.Timing.INDEX_FAST_COMMIT,
            " (image/screenshot shell row visibility)")
        observed("searchableCommit", IndexMetrics.Timing.INDEX_SEARCHABLE_COMMIT,
            " (image/screenshot OCR text committed before metadata/vector enrichment)")
        observed("metadata", IndexMetrics.Timing.META_EXTRACT)
        observed("embedding", IndexMetrics.Timing.EMBED)
        observed("total", IndexMetrics.Timing.INDEX_TOTAL)
        // Sprint P1 — document-path (PDF/Word/Excel/EPUB) stage timings from real runs.
        observed("doc.extraction", IndexMetrics.Timing.DOC_EXTRACT)
        observed("doc.dedup", IndexMetrics.Timing.DOC_DEDUP)
        observed("doc.total", IndexMetrics.Timing.DOC_INDEX_TOTAL)
        metrics.add(MetricValue(
            "observed.batch.documents",
            (snap[IndexMetrics.Event.INDEX_SUCCESS] ?: 0).toDouble(),
            "count", true, "successful indexes this process (per-batch volume)",
        ))

        // ── Sprint 4A: PDF text-trust gate (observed, this process) ──────────
        val pagesTrusted = snap[IndexMetrics.Event.PDF_PAGE_TRUSTED] ?: 0
        val pagesOcr = snap[IndexMetrics.Event.PDF_PAGE_OCR_FALLBACK] ?: 0
        val pagesTotal = pagesTrusted + pagesOcr
        val noPdfNote = if (pagesTotal == 0L) "no PDF pages extracted this process yet" else ""
        metrics.add(MetricValue("observed.pdf.pagesTrusted", pagesTrusted.toDouble(), "count", true, noPdfNote))
        metrics.add(MetricValue("observed.pdf.pagesOcrFallback", pagesOcr.toDouble(), "count", false, noPdfNote))
        metrics.add(MetricValue(
            "observed.pdf.ocrFallbackPercent",
            if (pagesTotal > 0) pagesOcr * 100.0 / pagesTotal else null,
            "%", false, noPdfNote.ifBlank { "share of PDF pages that failed the trust gate" },
        ))
        val trustScoreCount = snap["${IndexMetrics.Timing.PDF_TRUST_SCORE_MILLI}.count"] ?: 0
        metrics.add(MetricValue(
            "observed.pdf.trustScore.avg",
            if (trustScoreCount > 0) (snap["${IndexMetrics.Timing.PDF_TRUST_SCORE_MILLI}.avg"] ?: 0) / 1000.0 else null,
            "score", true, if (trustScoreCount > 0) "over $trustScoreCount page(s)" else noPdfNote,
        ))
        val ocrFallbackCount = snap["${IndexMetrics.Timing.PDF_OCR_FALLBACK}.count"] ?: 0
        metrics.add(MetricValue(
            "observed.pdf.ocrFallback.avg",
            if (ocrFallbackCount > 0) snap["${IndexMetrics.Timing.PDF_OCR_FALLBACK}.avg"]?.toDouble() else null,
            "ms/page", false,
            if (ocrFallbackCount > 0) "OCR ensemble per fallback page (since P3 the page render is separate: ocr.stage.render)" else noPdfNote,
        ))

        // ── Sprint P4: escalation-ladder outcomes (per fallback page) ────────
        val tier1Accepted = snap[IndexMetrics.Event.PDF_OCR_TIER1_ACCEPTED] ?: 0
        val escalated = snap[IndexMetrics.Event.PDF_OCR_ESCALATED] ?: 0
        val ladderTotal = tier1Accepted + escalated
        metrics.add(MetricValue(
            "observed.pdf.ocrTier1AcceptedPercent",
            if (ladderTotal > 0) tier1Accepted * 100.0 / ladderTotal else null,
            "%", true,
            if (ladderTotal > 0) "$tier1Accepted of $ladderTotal fallback page(s) accepted at tier 1 (2 passes instead of 7)"
            else "no fallback pages hit the escalation ladder this process yet",
        ))

        // ── Sprint P3: OCR ensemble stage timings (observed) ─────────────────
        // Samples cover BOTH ensemble consumers (screenshots/images AND PDF
        // fallback pages); one sample per engine invocation. Tesseract runs once
        // per input as a combined "eng+hin" pass — there is no separate
        // English/Hindi Tesseract stage to measure.
        val ocrStages = listOf(
            Triple("render", IndexMetrics.Timing.OCR_RENDER, "PDF fallback page render @150dpi (PDF path only)"),
            Triple("renderHiRes", IndexMetrics.Timing.OCR_RENDER_HIRES, "escalation-only 300dpi re-render (PDF path only)"),
            Triple("preprocess", IndexMetrics.Timing.OCR_PREPROCESS, "one bitmap conversion (grayscale/invert/upscale)"),
            Triple("mlkitEnglish", IndexMetrics.Timing.OCR_MLKIT_EN, "one ML Kit Latin inference (4 per ensemble run)"),
            Triple("mlkitHindi", IndexMetrics.Timing.OCR_MLKIT_HI, "one ML Kit Devanagari inference (2 per ensemble run)"),
            Triple("tesseract", IndexMetrics.Timing.OCR_TESSERACT, "one Tesseract eng+hin pass (combined — no separate EN/HI passes exist)"),
            // Sprint E4 — mutex wait BEFORE the Tesseract engine runs: the serialization-
            // amplification signal (one slow page stalls every other document's strategy 5).
            Triple("tesseractQueue", IndexMetrics.Timing.OCR_TESSERACT_QUEUE, "wait for the process-wide Tesseract mutex (excluded from 'tesseract')"),
            Triple("merge", IndexMetrics.Timing.OCR_MERGE, "line-level ensemble merge"),
            Triple("normalize", IndexMetrics.Timing.OCR_NFC, "NFC of merged OCR output"),
        )
        for ((label, key, desc) in ocrStages) {
            val c = snap["$key.count"] ?: 0
            metrics.add(MetricValue(
                "observed.ocr.stage.$label.avg",
                if (c > 0) snap["$key.avg"]?.toDouble() else null,
                "ms", false,
                if (c > 0) "$desc — over $c sample(s), image + PDF paths combined" else "no samples this process yet ($desc)",
            ))
        }

        // ── Sprint E4: pathological-OCR guard hit rates (observed, this process) ──
        // Denominator = Tesseract engine runs (one per ensemble invocation). A guard that
        // never fires on a healthy corpus is the EXPECTED result — these counters prove the
        // guards are surgical, not that they are broken.
        val tessRuns = snap["${IndexMetrics.Timing.OCR_TESSERACT}.count"] ?: 0
        val guardCounters = listOf(
            Triple("tessTimeout", IndexMetrics.Event.OCR_TESS_TIMEOUT, "watchdog fired (>${"%.0f".format(com.amar.vault.indexing.OcrPerfGuards.TESS_TIMEOUT_MS / 1000.0)}s recognition interrupted, partial text kept)"),
            Triple("tessInputDownscaled", IndexMetrics.Event.OCR_TESS_INPUT_DOWNSCALED, "Tesseract input > ${com.amar.vault.indexing.OcrPerfGuards.TESS_MAX_MP}MP downscaled for that pass only"),
            Triple("upscaleBypassed", IndexMetrics.Event.OCR_UPSCALE_BYPASSED, "strategy-4 2× upscale skipped on source ≥ ${com.amar.vault.indexing.OcrPerfGuards.UPSCALE_BYPASS_SRC_MP}MP (EN ran on shared grayscale)"),
        )
        for ((label, key, desc) in guardCounters) {
            val hits = snap[key] ?: 0
            metrics.add(MetricValue("observed.ocr.guard.$label", hits.toDouble(), "count", false, desc))
            metrics.add(MetricValue(
                "observed.ocr.guard.$label.hitRate",
                if (tessRuns > 0) hits * 100.0 / tessRuns else null,
                "%", false,
                if (tessRuns > 0) "of $tessRuns ensemble Tesseract run(s) this process" else "no ensemble runs this process yet",
            ))
        }

        // ── Sprint P1.1: PDF extraction sub-stage breakdown (observed) ───────
        // Every stage is nested inside doc.extract_ms; percentages are computed
        // against its total and are only meaningful when ONLY PDFs were indexed
        // this process (doc.extract_ms also covers Word/Excel/EPUB).
        val extractTotal = snap["${IndexMetrics.Timing.DOC_EXTRACT}.total"] ?: 0
        val stageKeys = listOf(
            "open" to IndexMetrics.Timing.PDF_OPEN,
            "load" to IndexMetrics.Timing.PDF_LOAD,
            "strip" to IndexMetrics.Timing.PDF_STRIP,          // page traversal + PDFTextStripper
            "nfc" to IndexMetrics.Timing.PDF_NFC,
            "trustScore" to IndexMetrics.Timing.PDF_TRUST,
            "ocrFallback" to IndexMetrics.Timing.PDF_OCR_FALLBACK,
            "chunking" to IndexMetrics.Timing.PDF_CHUNK,
        )
        var attributedMs = 0L
        for ((label, key) in stageKeys) {
            val stageCount = snap["$key.count"] ?: 0
            val stageTotal = snap["$key.total"]
            if (stageCount > 0 && stageTotal != null) attributedMs += stageTotal
            metrics.add(MetricValue(
                "observed.pdf.stage.$label.total",
                if (stageCount > 0) stageTotal?.toDouble() else null,
                "ms", false,
                if (stageCount > 0) "summed over $stageCount sample(s) this process" else noPdfNote,
            ))
            metrics.add(MetricValue(
                "observed.pdf.stage.$label.perPage",
                if (stageCount > 0 && stageTotal != null && pagesTotal > 0) stageTotal.toDouble() / pagesTotal else null,
                "ms/page", false,
                if (pagesTotal > 0) "stage total / $pagesTotal PDF page(s)" else noPdfNote,
            ))
            metrics.add(MetricValue(
                "observed.pdf.stage.$label.pctOfExtract",
                if (stageCount > 0 && stageTotal != null && extractTotal > 0) stageTotal * 100.0 / extractTotal else null,
                "%", false,
                if (extractTotal > 0) "of doc.extract_ms total (${extractTotal}ms); PDF-only sessions" else noPdfNote,
            ))
        }
        metrics.add(MetricValue(
            "observed.pdf.stage.unattributed.total",
            if (extractTotal > 0 && pagesTotal > 0) (extractTotal - attributedMs).toDouble() else null,
            "ms", false,
            "doc.extract_ms minus all instrumented stages — loop/dispatch overhead + ms rounding; " +
                "should stay near zero in a PDF-only session",
        ))

        // ── 2. Controlled micro-probes ────────────────────────────────────────
        metrics.add(timeProbe("probe.chunking.image", "ms") {
            WordWindowChunker().chunk(PROBE_TEXT).size
        })
        metrics.add(timeProbe("probe.chunking.document", "ms") {
            SentenceAwareChunker().chunk(PROBE_TEXT).size
        })
        metrics.add(roomWriteProbe())
        metrics.add(bm25AddProbe())
        metrics.add(extractionProbe())
        metrics.addAll(pdfStripBreakdownProbe())

        metrics.add(MetricValue("observed.room.avg", null, "ms/doc", false,
            "Room txn is not self-timed by the pipeline; see probe.roomWrite for a controlled measurement"))
        metrics.add(MetricValue("observed.bm25.avg", null, "ms/doc", false,
            "BM25 add is not self-timed by the pipeline; see probe.bm25Add for a controlled measurement"))

        return BenchmarkSection(
            id = "indexing",
            title = "Indexing stages (observed production timings + controlled probes)",
            metrics = metrics,
        )
    }

    private inline fun timeProbe(name: String, unit: String, block: () -> Any?): MetricValue {
        return try {
            block() // warm-up
            val times = LongArray(PROBE_ROUNDS) {
                val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1_000_000
            }
            MetricValue(name, times.average(), unit, false, "avg of $PROBE_ROUNDS rounds")
        } catch (e: Exception) {
            MetricValue(name, null, unit, false, "probe failed: ${e.message}")
        }
    }

    private suspend fun roomWriteProbe(): MetricValue = try {
        val dao = db.vaultDao()
        val times = LongArray(PROBE_ROUNDS) {
            val id = "benchmark-probe-${UUID.randomUUID()}"
            val item = VaultItem(
                id = id, uri = "benchmark://probe", ocrText = PROBE_TEXT.take(2000),
                lang = "en", itemType = "benchmark_probe", timestamp = System.currentTimeMillis(),
            )
            val t0 = System.nanoTime()
            dao.insert(item)
            val elapsed = (System.nanoTime() - t0) / 1_000_000
            dao.deleteById(id) // cleanup, untimed
            elapsed
        }
        MetricValue("probe.roomWrite", times.average(), "ms", false,
            "insert of one item row via production DAO (probe row deleted immediately)")
    } catch (e: Exception) {
        MetricValue("probe.roomWrite", null, "ms", false, "probe failed: ${e.message}")
    }

    private fun bm25AddProbe(): MetricValue = try {
        // Unique nonsense tokens: cannot collide with any real query; the in-memory
        // entry disappears at next launch (BM25 rehydrates from Room, where no probe row exists).
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val text = (1..50).joinToString(" ") { "zzprobe${nonce}tok$it" }
        val t0 = System.nanoTime()
        bm25.addDocument("benchmark-probe-$nonce", text)
        MetricValue("probe.bm25Add", (System.nanoTime() - t0) / 1_000_000.0, "ms", false,
            "one 50-token document into the live native BM25 index (transient until restart)")
    } catch (e: Exception) {
        MetricValue("probe.bm25Add", null, "ms", false, "probe failed: ${e.message}")
    }

    private fun firstBenchmarkPdf(): File? {
        val mediaDir = File(context.filesDir, "${GoldenDatasetStore.DEVICE_DIR}/media")
        return mediaDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }?.firstOrNull()
    }

    private suspend fun extractionProbe(): MetricValue {
        val pdf = firstBenchmarkPdf()
            ?: return MetricValue("probe.extraction.pdf", null, "ms", false,
                "no PDF at filesDir/${GoldenDatasetStore.DEVICE_DIR}/media/ — adb-push one to enable")
        return try {
            val extractor = DocumentContentExtractor()
            val t0 = System.nanoTime()
            val content = extractor.extract(context, Uri.fromFile(pdf), "application/pdf")
            val ms = (System.nanoTime() - t0) / 1_000_000.0
            MetricValue("probe.extraction.pdf", ms, "ms", false,
                "${pdf.name}: ${content.pagedChunks.size} chunk(s) extracted (read-only)")
        } catch (e: Exception) {
            MetricValue("probe.extraction.pdf", null, "ms", false, "probe failed: ${e.message}")
        }
    }

    /**
     * Decomposes the per-page `PDFTextStripper.getText()` cost into its three parts so a
     * fix can be chosen from data instead of guessing. Read-only: opens the benchmark PDF
     * and re-strips it a few times; changes nothing in the index. Diagnostic only — the
     * production path (FormatExtractor) is untouched.
     *
     * Method — three warm passes over ONE open [PDDocument] (font programs are cached on
     * the doc after the first pass, so later passes reuse them):
     *   1. cold, suppress ON  → parses + caches all fonts; mirrors the single production pass.
     *   2. warm, suppress ON  → fonts cached ⇒ isolates strip + dedup.
     *   3. warm, suppress OFF → fonts cached, no overlap filter ⇒ isolates strip alone.
     *
     * Derived per page:
     *   - fontParse    = cold − warmOn   (one-time font-program parsing) → INHERENT
     *   - dedup        = warmOn − warmOff (the O(n²) overlap filter)     → the ONLY safe lever
     *   - residualStrip= warmOff          (tokenize + positioning + NFC) → INHERENT
     *   - total        = cold             (= what production actually pays)
     *
     * `dedupSafeOnThisPdf` compares suppress-on vs suppress-off output: identical ⇒ disabling
     * the filter would NOT change indexed text on this PDF (a signal, not a guarantee for all PDFs).
     */
    private fun pdfStripBreakdownProbe(): List<MetricValue> {
        val prefix = "probe.pdfStrip"
        val pdf = firstBenchmarkPdf()
            ?: return listOf(MetricValue("$prefix.pages", null, "count", true,
                "no PDF at filesDir/${GoldenDatasetStore.DEVICE_DIR}/media/ — adb-push one to enable"))
        return try {
            PDDocument.load(pdf).use { doc ->
                // Sample a bounded window so the diagnostic (3 re-strips) stays cheap on large
                // PDFs — per-page figures are representative regardless of window size.
                val total = doc.numberOfPages.coerceAtLeast(1)
                val pages = total.coerceAtMost(SAMPLE_PAGES)

                fun timeStrip(suppress: Boolean): Pair<Long, CountingStripper> {
                    val s = CountingStripper(suppress).apply { startPage = 1; endPage = pages }
                    val t0 = System.nanoTime()
                    s.captured = s.getText(doc)
                    return (System.nanoTime() - t0) / 1_000_000 to s
                }

                val (coldMs, _)        = timeStrip(true)          // cold: warms the font cache
                val (warmOnMs, on)     = timeStrip(true)          // warm, dedup on
                val (warmOffMs, off)   = timeStrip(false)         // warm, dedup off

                val fontParse = (coldMs - warmOnMs).coerceAtLeast(0)
                val dedup     = (warmOnMs - warmOffMs).coerceAtLeast(0)
                val identical = on.captured == off.captured

                listOf(
                    MetricValue("$prefix.pages", pages.toDouble(), "count", true,
                        "${pdf.name}: sampled $pages of $total page(s) (diagnostic; index unchanged)"),
                    MetricValue("$prefix.glyphsPerPage", off.glyphs.toDouble() / pages, "count", false,
                        "text positions processed / page — high ⇒ dense text (dedup-bound); low but slow ⇒ font-bound"),
                    MetricValue("$prefix.total.perPage", coldMs.toDouble() / pages, "ms/page", false,
                        "cold getText (fonts uncached) — matches the single production pass"),
                    MetricValue("$prefix.fontParse.perPage", fontParse.toDouble() / pages, "ms/page", false,
                        "cold − warm getText = one-time font-program parsing; INHERENT, not safely removable"),
                    MetricValue("$prefix.dedup.perPage", dedup.toDouble() / pages, "ms/page", false,
                        "warm(suppressOn) − warm(suppressOff) = the O(n²) overlap filter; the ONLY safely-tunable cost"),
                    MetricValue("$prefix.residualStrip.perPage", warmOffMs.toDouble() / pages, "ms/page", false,
                        "warm getText, dedup off = tokenize + glyph positioning + NFC; INHERENT"),
                    MetricValue("$prefix.dedupSafeOnThisPdf", if (identical) 1.0 else 0.0, "bool", true,
                        if (identical)
                            "suppress-off output IDENTICAL on this PDF — disabling the filter is safe HERE (not a guarantee for all PDFs)"
                        else
                            "suppress-off output DIFFERS on this PDF — disabling WOULD change indexed text (doubled/overlapping glyphs)"),
                )
            }
        } catch (e: Exception) {
            listOf(MetricValue("$prefix.pages", null, "count", true, "probe failed: ${e.message}"))
        }
    }

    /**
     * [PDFTextStripper] subclass used only by [pdfStripBreakdownProbe]: counts every text
     * position the engine processes (dedup-independent — suppression filters later, in
     * writePage), and lets [suppressDuplicateOverlappingText] be toggled per instance.
     * [sortByPosition] is left at the production default (false).
     */
    private class CountingStripper(suppress: Boolean) : PDFTextStripper() {
        var glyphs = 0L
            private set
        var captured: String = ""
        init {
            sortByPosition = false
            suppressDuplicateOverlappingText = suppress
        }
        override fun processTextPosition(text: TextPosition) {
            glyphs++
            super.processTextPosition(text)
        }
    }
}
