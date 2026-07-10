package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.amar.vault.IndexMetrics
import com.amar.vault.UnicodeText
import com.amar.vault.VaultLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream

/** One extracted, chunked unit of a document. Moved verbatim from DocumentIndexer. */
data class PagedChunk(val text: String, val pdfPage: Int?, val chunkIndex: Int)

/**
 * A single document-format handler — the validated extensibility point.
 *
 * Adding a future format = add one `FormatExtractor` to [DocumentFormatRegistry.DEFAULT];
 * `DocumentIndexer` and `DocumentContentExtractor` are untouched. No reflection, no runtime
 * plugin system — a plain static list.
 *
 * Extractors are stateless (context is passed per call), so they can be shared singletons.
 * Each owns its own parsing algorithm, verbatim from the prior `DocumentIndexer` methods.
 */
interface FormatExtractor {
    val mimeTypes: Set<String>
    /** Family tag prepended to generated tags (was DocFamily.tag). */
    val tag: String
    /** VaultItem.itemType for chunks of this family (was DocFamily.itemType). */
    val itemType: String
    /** Suspend since Sprint 4A: the PDF extractor may run the (suspend) OCR fallback. */
    suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk>
}

internal fun openDocStream(context: Context, uri: Uri): InputStream =
    context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open $uri")

/**
 * PDF — page-by-page text extraction, chunked per page (preserves pdfPage numbers).
 *
 * Sprint 4A — per-page text-trust gate (implements the approved architecture review):
 *
 *   stripper text → NFC → [TextTrustScorer] → trusted?
 *     ├─ yes: continue exactly as before (chunk the stripper text)
 *     └─ no:  render THIS page only (PDFBox [PDFRenderer], honors /Rotate) →
 *             the existing [ImageContentExtractor] OCR ensemble → chunk the OCR text
 *
 * Healthy English/Hindi digital pages pass the gate and never touch a bitmap; the
 * OCR engine and the page renderer are built lazily, only when a page actually fails.
 * Pages are processed sequentially and each rendered bitmap is recycled immediately —
 * bounded memory, no retention. The same open [PDDocument] serves both the stripper
 * and the renderer (no duplicate parsing).
 */
class PdfFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/pdf")
    override val tag = "pdf document"
    override val itemType = "pdf"

    private companion object {
        const val TAG = "PdfTrustGate"
        /** ML Kit sweet spot vs. RAM: A4 @150dpi ≈ 1240×1754 RGB, recycled per page. */
        const val OCR_RENDER_DPI = 150f
        /**
         * Sprint P4 — escalation-only re-render. Same pixel count as the ensemble's
         * legacy 2× upscale of the 150dpi render, but rendered sharp from the vector
         * source instead of interpolated from raster. Used only when tier 1 escalates.
         */
        const val ESCALATION_RENDER_DPI = 300f

        /** Round-to-nearest ms — per-doc nano accumulators would floor to 0 with integer division. */
        fun nsToMs(ns: Long): Long = (ns + 500_000) / 1_000_000
    }

    /**
     * One rendered fallback page handed from the strip/render producer to the OCR
     * consumer. The consumer owns (and recycles) [bitmap]; [strippedFallback] is the
     * NFC'd stripper text used verbatim when OCR fails — same semantics as the
     * pre-P3 serial catch branch. [renderHiRes] (Sprint P4) re-renders this page at
     * [ESCALATION_RENDER_DPI] for the ensemble's strategy 4, serialized against the
     * stripper via the document mutex (PDFBox allows no concurrent access to one doc).
     */
    private class OcrJob(
        val page: Int,
        val bitmap: Bitmap,
        val strippedFallback: String,
        val renderHiRes: suspend () -> Bitmap?,
    )

    // THE existing OCR engine, reused — created lazily so PDFs whose every page passes
    // the gate (all healthy digital PDFs) never construct it. One instance for the whole
    // document path (this extractor is a shared singleton via DocumentFormatRegistry.DEFAULT).
    @Volatile
    private var ocrEngine: ImageContentExtractor? = null

    private fun ocrEngine(context: Context): ImageContentExtractor =
        ocrEngine ?: synchronized(this) {
            ocrEngine ?: ImageContentExtractor(context.applicationContext).also { ocrEngine = it }
        }

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val result = mutableListOf<PagedChunk>()
        var chunkIdx = 0

        // Sprint E3 — observation-only doc/page profiler, found via the coroutine context
        // (null unless the capture toggle is on or a benchmark probe supplied one). Every
        // `prof?.…` below feeds an ALREADY-measured elapsed value; nothing is re-timed and
        // no control flow depends on it.
        val prof = kotlin.coroutines.coroutineContext[DocProfileRecorder]

        // Sprint P1.1 — sub-stage accumulators (nanos). Per-page stages are recorded
        // once per DOCUMENT (see IndexMetrics.Timing docs); flushed in the finally
        // below so a mid-loop failure still reports partial time, matching the
        // partial-on-failure semantics of the enclosing DOC_EXTRACT timer.
        var stripNs = 0L
        var nfcNs = 0L
        var trustNs = 0L
        var chunkNs = 0L

        val tOpen = System.nanoTime()
        val stream = openDocStream(context, uri)
        val openNs = System.nanoTime() - tOpen
        IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_OPEN, nsToMs(openNs))
        prof?.stageNs(ProfilerStage.PDF_OPEN, openNs)
        stream.use {
            val tLoad = System.nanoTime()
            val doc = PDDocument.load(stream)
            val loadNs = System.nanoTime() - tLoad
            IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_LOAD, nsToMs(loadNs))
            prof?.stageNs(ProfilerStage.PDF_LOAD, loadNs)
            doc.use {
                try {
                val pageCount = doc.numberOfPages
                prof?.pageCount = pageCount
                // Per-page final text, 1-indexed. Written by the strip/render producer
                // (trusted pages, render failures) and the OCR consumer (fallback pages)
                // at DISJOINT indices; read only after coroutineScope joins both, which
                // provides the happens-before edge.
                val pageTexts = arrayOfNulls<String>(pageCount + 1)

                // Sprint P3 — pipeline overlap. The producer owns PDDocument (PDFBox is
                // not thread-safe per document): it strips, scores and RENDERS on this
                // thread. Rendered pages are handed to a single FIFO consumer that runs
                // the unchanged OCR ensemble — so the render/strip of page N+1 overlaps
                // the OCR of page N. One consumer receiving in send order ⇒ OCR executes
                // strictly in page order, one page at a time, exactly like the serial
                // loop. Channel capacity 1 bounds memory to ≤2 rendered bitmaps alive.
                coroutineScope {
                    // Serializes ALL PDDocument access: the producer's stripping/rendering
                    // and the consumer's escalation-only 300dpi re-render. Uncontended on
                    // the hot path — the consumer takes it only when a page escalates.
                    val docMutex = Mutex()
                    val jobs = Channel<OcrJob>(
                        capacity = 1,
                        onUndeliveredElement = { it.bitmap.recycle() },
                    )
                    launch {
                        for (job in jobs) {
                            val t0 = System.currentTimeMillis()
                            // Sprint E3: route the OCR tier outcome (observed inside
                            // extractPdfPageText) to this page. Single sequential consumer
                            // ⇒ one in-flight OCR page per document at a time.
                            prof?.beginPageOcr(job.page)
                            try {
                                // OCR output is NFC-normalized at its own source (ImageContentExtractor).
                                // Text-only: the QR payloads were never consumed on this path.
                                // Sprint P4: escalation-ladder OCR — tier 1 (raw EN+HI) with
                                // full-ensemble escalation; see extractPdfPageText.
                                pageTexts[job.page] = ocrEngine(context)
                                    .extractPdfPageText(job.bitmap, job.renderHiRes)
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: Exception) {
                                VaultLog.w(TAG, "Page ${job.page} OCR fallback failed: ${e.message} — keeping stripper text")
                                pageTexts[job.page] = job.strippedFallback
                            } finally {
                                job.bitmap.recycle()
                                val ocrMs = System.currentTimeMillis() - t0
                                IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_OCR_FALLBACK, ocrMs)
                                prof?.pageOcrDone(job.page, ocrMs)
                            }
                        }
                    }
                    try {
                        val stripper = PDFTextStripper()
                        var tStage = System.nanoTime()

                        // Task 3: Use a unique sentinel separator. Verify it doesn't collide.
                        var sentinel: String
                        var allRaw: String
                        val originalPageEnd = stripper.pageEnd
                        while (true) {
                            sentinel = "[[[PAGE_SEP_${java.util.UUID.randomUUID()}]]]"
                            // Preserve original pageEnd (usually \n) to maintain byte-identical output,
                            // then append our sentinel.
                            stripper.pageEnd = "$originalPageEnd$sentinel"
                            
                            allRaw = docMutex.withLock { stripper.getText(doc) }
                            
                            // Verify the sentinel only occurs at the end of each extracted page.
                            // split().size - 1 == pageCount guarantees no collisions in the source text.
                            if (allRaw.split(sentinel).size - 1 == pageCount) {
                                break
                            }
                        }
                        stripNs += System.nanoTime() - tStage
                        val rawPages = allRaw.split(sentinel)

                        var renderer: PDFRenderer? = null // built once, only if some page needs OCR
                        for (page in 1..pageCount) {
                            val raw = rawPages.getOrElse(page - 1) { "" }
                            // NFC at the ingestion boundary — BEFORE scoring and chunking, so the
                            // gate judges (and Room/BM25 store) canonical bytes.
                            tStage = System.nanoTime()
                            val stripped = UnicodeText.nfc(raw.trim())
                            val pageNfcNs = System.nanoTime() - tStage
                            nfcNs += pageNfcNs
                            tStage = System.nanoTime()
                            val trust = TextTrustScorer.score(stripped)
                            val pageTrustNs = System.nanoTime() - tStage
                            trustNs += pageTrustNs
                            IndexMetrics.recordDuration(
                                IndexMetrics.Timing.PDF_TRUST_SCORE_MILLI, (trust.score * 1000).toLong()
                            )
                            prof?.pageScored(page, pageNfcNs, pageTrustNs, trust.score.toDouble(), trust.trusted)

                            if (trust.trusted) {
                                IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_TRUSTED)
                                pageTexts[page] = stripped
                            } else {
                                VaultLog.d(TAG, buildString {
                                    append("Page $page\nTrusted: false (score=${"%.2f".format(trust.score)})\nReasons:")
                                    trust.reasons.forEach { append("\n• $it") }
                                    append("\nOCR fallback executed.")
                                })
                                IndexMetrics.increment(IndexMetrics.Event.PDF_PAGE_OCR_FALLBACK)
                                try {
                                    val r = renderer ?: PDFRenderer(doc).also { renderer = it }
                                    val tRender = System.currentTimeMillis()
                                    val bitmap = docMutex.withLock {
                                        r.renderImageWithDPI(page - 1, OCR_RENDER_DPI, ImageType.RGB)
                                    }
                                    val renderMs = System.currentTimeMillis() - tRender
                                    IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_RENDER, renderMs)
                                    prof?.pageRendered(page, renderMs, bitmap.width, bitmap.height, bitmap.byteCount.toLong())
                                    // Escalation-only 300dpi re-render for ensemble strategy 4
                                    // (Sprint P4 #2) — runs on the consumer, so it must take the
                                    // document mutex. Failure → null → the ensemble falls back
                                    // to its legacy 2× upscale of the 150dpi render.
                                    val renderHiRes: suspend () -> Bitmap? = {
                                        docMutex.withLock {
                                            val tHi = System.currentTimeMillis()
                                            try {
                                                r.renderImageWithDPI(page - 1, ESCALATION_RENDER_DPI, ImageType.RGB)
                                                    .also { hi ->
                                                        prof?.pageHiResRendered(page, hi.width, hi.height, hi.byteCount.toLong())
                                                    }
                                            } finally {
                                                val hiMs = System.currentTimeMillis() - tHi
                                                IndexMetrics.recordDuration(IndexMetrics.Timing.OCR_RENDER_HIRES, hiMs)
                                                prof?.pageHiResMs(page, hiMs)
                                            }
                                        }
                                    }
                                    jobs.send(OcrJob(page, bitmap, stripped, renderHiRes))
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    // Render failed → keep stripper text (unchanged semantics).
                                    VaultLog.w(TAG, "Page $page OCR fallback failed: ${e.message} — keeping stripper text")
                                    pageTexts[page] = stripped
                                }
                            }
                        }
                    } finally {
                        jobs.close() // consumer drains what was sent, then completes
                    }
                }

                // Chunk in strict page order AFTER every page's text is final —
                // identical chunk inputs, ordering and chunkIdx numbering to the
                // pre-P3 serial loop (the chunker is pure).
                for (page in 1..pageCount) {
                    val pageText = pageTexts[page] ?: continue
                    if (pageText.isBlank()) continue
                    val tChunk = System.nanoTime()
                    chunker.chunk(pageText).forEach { chunk ->
                        result.add(PagedChunk(chunk, page, chunkIdx++))
                    }
                    chunkNs += System.nanoTime() - tChunk
                }
                } finally {
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_STRIP, nsToMs(stripNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_NFC, nsToMs(nfcNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_TRUST, nsToMs(trustNs))
                    IndexMetrics.recordDuration(IndexMetrics.Timing.PDF_CHUNK, nsToMs(chunkNs))
                    // Sprint E3 — same accumulators, attributed to this document (observation only).
                    prof?.stageNs(ProfilerStage.PDF_STRIP, stripNs)
                    prof?.stageNs(ProfilerStage.PDF_NFC, nfcNs)
                    prof?.stageNs(ProfilerStage.PDF_TRUST, trustNs)
                    prof?.stageNs(ProfilerStage.PDF_CHUNK, chunkNs)
                }
            }
        }
        return result
    }
}

/** DOCX — whole-document text, then chunked. */
class WordFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    override val tag = "word document"
    override val itemType = "word"

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val text = openDocStream(context, uri).use {
            org.apache.poi.xwpf.extractor.XWPFWordExtractor(
                org.apache.poi.xwpf.usermodel.XWPFDocument(it)
            ).text
        }
        return chunker.chunk(text).mapIndexed { idx, chunk -> PagedChunk(chunk, null, idx) }
    }
}

/** XLSX — flattened sheet text, then chunked. */
class ExcelFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    override val tag = "spreadsheet excel"
    override val itemType = "excel"

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val fmt = org.apache.poi.ss.usermodel.DataFormatter()
        val text = openDocStream(context, uri).use {
            buildExcelText(org.apache.poi.xssf.usermodel.XSSFWorkbook(it), fmt)
        }
        return chunker.chunk(text).mapIndexed { idx, chunk -> PagedChunk(chunk, null, idx) }
    }

    private fun buildExcelText(
        wb: org.apache.poi.ss.usermodel.Workbook,
        fmt: org.apache.poi.ss.usermodel.DataFormatter,
    ) = buildString {
        wb.use { w -> for (s in w) { appendLine("═══ ${s.sheetName} ═══"); for (r in s) { val c = r.mapNotNull { fmt.formatCellValue(it).takeIf { v -> v.isNotBlank() } }; if (c.isNotEmpty()) appendLine(c.joinToString(" | ")) }; appendLine() } }
    }
}

/** EPUB — spine-ordered HTML text, then chunked. */
class EpubFormatExtractor : FormatExtractor {
    override val mimeTypes = setOf("application/epub+zip")
    override val tag = "ebook epub"
    override val itemType = "epub"

    override suspend fun extract(context: Context, uri: Uri, chunker: Chunker): List<PagedChunk> {
        val text = openDocStream(context, uri).use { stream ->
            val zipBytes = stream.readBytes()
            val fileMap = mutableMapOf<String, String>()
            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if (n.endsWith(".html") || n.endsWith(".xhtml") || n.endsWith(".htm") || n.endsWith(".opf"))
                        fileMap[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
            val opf = fileMap.entries.firstOrNull { it.key.endsWith(".opf") }?.value
            val paths = parseSpineOrder(opf, fileMap.keys)
            buildString { paths.forEach { p -> fileMap[p]?.let { val c = stripHtml(it); if (c.isNotBlank()) appendLine(c) } } }
        }
        return chunker.chunk(text).mapIndexed { idx, chunk -> PagedChunk(chunk, null, idx) }
    }

    private fun parseSpineOrder(opfContent: String?, allPaths: Set<String>): List<String> {
        val htmlFilter = { p: String -> val l = p.lowercase(); l.endsWith(".html") || l.endsWith(".xhtml") || l.endsWith(".htm") }
        if (opfContent == null) return allPaths.filter(htmlFilter).sorted()
        val manifest = mutableMapOf<String, String>()
        Regex("""<item\s[^>]*id="([^"]+)"[^>]*href="([^"]+)"[^>]*/?>""").findAll(opfContent).forEach { manifest[it.groupValues[1]] = it.groupValues[2] }
        val spineIds = Regex("""<itemref\s[^>]*idref="([^"]+)"[^>]*/?>""").findAll(opfContent).map { it.groupValues[1] }.toList()
        if (spineIds.isEmpty() || manifest.isEmpty()) return allPaths.filter(htmlFilter).sorted()
        val opfDir = allPaths.firstOrNull { it.endsWith(".opf") }?.substringBeforeLast("/", "")?.let { if (it.isNotEmpty()) "$it/" else "" } ?: ""
        return spineIds.mapNotNull { id -> manifest[id]?.let { href -> allPaths.firstOrNull { it == opfDir + href || it.endsWith(href) } } }
    }

    private fun stripHtml(html: String) = html
        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), " ")
        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("&\\w+;"), " ").replace(Regex("\\s+"), " ").trim()
}

/**
 * Static dispatch table mime → extractor. No reflection; just a map built from a plain list.
 */
class DocumentFormatRegistry(extractors: List<FormatExtractor> = DEFAULT) {
    private val byMime: Map<String, FormatExtractor> =
        extractors.flatMap { e -> e.mimeTypes.map { it to e } }.toMap()

    fun extractorFor(mimeType: String): FormatExtractor? = byMime[mimeType]
    fun isSupported(mimeType: String): Boolean = byMime.containsKey(mimeType)
    val supportedMimeTypes: Set<String> get() = byMime.keys

    companion object {
        /** Register a new format here — nothing else changes. */
        val DEFAULT: List<FormatExtractor> = listOf(
            PdfFormatExtractor(),
            WordFormatExtractor(),
            ExcelFormatExtractor(),
            EpubFormatExtractor(),
        )
    }
}
