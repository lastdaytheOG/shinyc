package com.amar.vault.indexing

import com.amar.vault.VaultLog
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Sprint E3 — slowest-document / slowest-page indexing profiler.
 *
 * PURE OBSERVATION. Nothing here changes indexing, OCR, retrieval, ordering or threading.
 * It attributes already-measured wall times to the document (and PDF page) that incurred
 * them, so the benchmark can rank WHERE indexing time goes. No optimization lives here.
 *
 * Design (mirrors [OcrInstrumentation]):
 *  - Disabled by default. [IndexingProfiler.beginOrNull] returns null when disabled, and every
 *    hook in the pipeline is a `rec?.…` no-op — zero behaviour change, negligible overhead.
 *  - When enabled (developer capture toggle), DocumentIndexer / IndexingPipeline create one
 *    [DocProfileRecorder] per document and publish the built [DocProfile] into a bounded buffer.
 *  - The recorder is a [CoroutineContext] element: deep stages (PdfFormatExtractor's per-page
 *    strip/trust/render/OCR, the OCR tier outcome inside ImageContentExtractor) find it via
 *    `coroutineContext[DocProfileRecorder]` — no production signature changes.
 *  - The benchmark probe creates recorders directly (never touching this buffer), so a
 *    benchmark run cannot pollute captured production samples.
 *
 * Measurement honesty:
 *  - Every value is measured at an existing timing site (the hooks reuse the exact elapsed
 *    values already fed to [com.amar.vault.IndexMetrics]) or is explicitly derived and labeled.
 *  - Per-page STRIP time cannot be measured individually — production strips the whole document
 *    in ONE `PDFTextStripper.getText` call. [PageProfile.stripShareMs] is the measured document
 *    strip total amortized over pages, and is labeled as such everywhere it is reported.
 *  - Stage sums are compute time. The PDF pipeline overlaps strip/render of page N+1 with OCR
 *    of page N, so per-page/per-doc stage sums may legitimately exceed wall-clock totals.
 */

/** Stage keys for [DocProfile.stagesMs]. Doc-level; page-level stages live on [PageProfile]. */
object ProfilerStage {
    const val EXTRACT = "extract"        // whole content extraction (PDF sub-stages nest inside)
    const val DEDUP = "dedup"
    const val ROOM = "room"              // Room txn (chunk rows / image item)
    const val BM25 = "bm25"              // BM25 add (document path)
    const val EMBED = "embed"            // embed + HNSW add (image path)
    const val META = "meta"              // metadata extraction (image path)
    const val OCR_IMAGE = "ocrImage"     // whole-image OCR ensemble (image path)
    const val PDF_OPEN = "pdfOpen"
    const val PDF_LOAD = "pdfLoad"
    const val PDF_STRIP = "pdfStrip"     // ONE whole-document stripper pass
    const val PDF_NFC = "pdfNfc"
    const val PDF_TRUST = "pdfTrust"
    const val PDF_CHUNK = "pdfChunk"
    /** Derived in [DocProfileRecorder.build]: sum of per-page render (+hi-res) times. */
    const val RENDER = "render"
    /** Derived in [DocProfileRecorder.build]: sum of per-page OCR-fallback times. */
    const val PAGE_OCR = "pageOcr"
}

/** One PDF page's measured stage timings. Only [stripShareMs] is derived (and labeled so). */
data class PageProfile(
    val page: Int,
    /** Document strip total / page count — strip is one whole-doc pass, NOT individually measured. */
    val stripShareMs: Double,
    val nfcMs: Double,
    val trustMs: Double,
    val trustScore: Double?,
    val trusted: Boolean?,
    val ocrUsed: Boolean,
    /** null = not an OCR page (or outcome not observed); true = tier 1 accepted; false = escalated. */
    val ocrTier1Accepted: Boolean?,
    val renderMs: Double,
    val hiResRenderMs: Double,
    val ocrMs: Double,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val bitmapBytes: Long,
    /** Sum of measured per-page stages + stripShare. Compute time, not wall (stages overlap). */
    val pageTotalMs: Double,
)

/** One document's full indexing profile. */
data class DocProfile(
    val docId: String,
    val displayName: String,
    val fileType: String,       // pdf / word / excel / epub / screenshot / image / photo…
    val origin: String,         // "production" (capture toggle) or "benchmark-probe"
    val status: String,         // "success", "failed:…", "probe"
    val totalMs: Double,
    val stagesMs: Map<String, Double>,
    val pageCount: Int,
    val trustedPages: Int,
    val ocrFallbackPages: Int,
    val avgTrustScore: Double?,
    val largestBitmapWidth: Int,
    val largestBitmapHeight: Int,
    /** Byte count of the largest SINGLE rendered bitmap. True peak concurrent bitmap memory is
     *  not observable without touching OCR internals, so it is deliberately not reported. */
    val largestBitmapBytes: Long,
    val pages: List<PageProfile>,
    val capturedAtMs: Long = System.currentTimeMillis(),
) {
    fun stage(key: String): Double = stagesMs[key] ?: 0.0
}

/**
 * Per-document recorder, created only when profiling is on (or by the benchmark probe).
 * Also a [CoroutineContext] element so nested pipeline stages can attribute their existing
 * measured timings to the right document/page without signature changes.
 *
 * Thread-safety: the document path runs producer + OCR-consumer coroutines concurrently, but
 * each touches DISJOINT page numbers' fields at a time (single FIFO consumer, page-ordered),
 * and stage sums use ConcurrentHashMap.merge. [ocrPage] is set by the consumer strictly before
 * it calls the OCR engine on that page (same coroutine), so the tier-outcome routing is safe.
 */
class DocProfileRecorder(
    val docId: String,
    val displayName: String,
    @Volatile var fileType: String,
    val origin: String = "production",
) : AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<DocProfileRecorder> {
        /** Safety cap — a pathological million-"page" input must not balloon memory. */
        private const val MAX_PAGES = 5_000
    }

    private val stages = ConcurrentHashMap<String, Double>()
    private val pages = ConcurrentHashMap<Int, PageRec>()

    @Volatile
    var pageCount: Int = 0

    @Volatile
    private var ocrPage: Int = Int.MIN_VALUE

    // Largest single rendered bitmap (updates are rare — one per fallback page render).
    private val bitmapLock = Any()
    private var largestW = 0
    private var largestH = 0
    private var largestBytes = 0L

    private class PageRec {
        @Volatile var nfcNs = 0L
        @Volatile var trustNs = 0L
        @Volatile var trustScore = Double.NaN
        @Volatile var trusted: Boolean? = null
        @Volatile var renderMs = 0L
        @Volatile var hiResMs = 0L
        @Volatile var ocrMs = 0L
        @Volatile var ocrUsed = false
        @Volatile var tierAccepted: Boolean? = null
        @Volatile var bmpW = 0
        @Volatile var bmpH = 0
        @Volatile var bmpBytes = 0L
    }

    private fun page(n: Int): PageRec? =
        pages[n] ?: if (pages.size >= MAX_PAGES) null else pages.computeIfAbsent(n) { PageRec() }

    /** Accumulate an already-measured stage duration (ms). */
    fun stageMs(stage: String, ms: Long) {
        stages.merge(stage, ms.toDouble(), Double::plus)
    }

    /** Accumulate an already-measured stage duration (ns). */
    fun stageNs(stage: String, ns: Long) {
        stages.merge(stage, ns / 1e6, Double::plus)
    }

    /** Producer loop: this page's measured NFC + trust-scoring times and the gate verdict. */
    fun pageScored(pageNum: Int, nfcNs: Long, trustNs: Long, score: Double, trusted: Boolean) {
        page(pageNum)?.let {
            it.nfcNs = nfcNs
            it.trustNs = trustNs
            it.trustScore = score
            it.trusted = trusted
        }
    }

    /** Producer: this fallback page's measured 150dpi render time + bitmap geometry. */
    fun pageRendered(pageNum: Int, ms: Long, width: Int, height: Int, bytes: Long) {
        page(pageNum)?.let {
            it.renderMs = ms
            it.bmpW = width
            it.bmpH = height
            it.bmpBytes = bytes
        }
        recordBitmap(width, height, bytes)
    }

    /** Consumer (escalation only): the measured 300dpi re-render time / geometry. */
    fun pageHiResRendered(pageNum: Int, width: Int, height: Int, bytes: Long) {
        recordBitmap(width, height, bytes)
    }

    fun pageHiResMs(pageNum: Int, ms: Long) {
        page(pageNum)?.let { it.hiResMs += ms }
    }

    /** Consumer: about to run the OCR engine on this page (routes the tier outcome). */
    fun beginPageOcr(pageNum: Int) {
        ocrPage = pageNum
    }

    /** Consumer: this page's measured whole-OCR (ladder) wall time. */
    fun pageOcrDone(pageNum: Int, ms: Long) {
        page(pageNum)?.let {
            it.ocrMs = ms
            it.ocrUsed = true
        }
    }

    /**
     * Called from the OCR ladder (observation only): tier-1 accepted vs full-ensemble escalation.
     * [pageNum] routes the outcome to an explicit page so attribution stays correct when several
     * OCR fallback pages run concurrently (Sprint P5). Callers without an [OcrPageTag] (the single
     * image/screenshot path) pass null and fall back to the last [beginPageOcr] page.
     */
    fun ocrTierOutcome(accepted: Boolean, pageNum: Int? = null) {
        pages[pageNum ?: ocrPage]?.let { it.tierAccepted = accepted }
    }

    private fun recordBitmap(width: Int, height: Int, bytes: Long) {
        synchronized(bitmapLock) {
            if (bytes > largestBytes) {
                largestBytes = bytes
                largestW = width
                largestH = height
            }
        }
    }

    /** Freeze into an immutable [DocProfile]. Derived values are computed (and labeled) here. */
    fun build(totalMs: Long, status: String): DocProfile {
        val stripTotal = stages[ProfilerStage.PDF_STRIP] ?: 0.0
        val stripShare = if (pageCount > 0) stripTotal / pageCount else 0.0

        val pageProfiles = pages.entries.sortedBy { it.key }.map { (n, p) ->
            val nfc = p.nfcNs / 1e6
            val trust = p.trustNs / 1e6
            PageProfile(
                page = n,
                stripShareMs = stripShare,
                nfcMs = nfc,
                trustMs = trust,
                trustScore = p.trustScore.takeUnless { it.isNaN() },
                trusted = p.trusted,
                ocrUsed = p.ocrUsed,
                ocrTier1Accepted = p.tierAccepted,
                renderMs = p.renderMs.toDouble(),
                hiResRenderMs = p.hiResMs.toDouble(),
                ocrMs = p.ocrMs.toDouble(),
                bitmapWidth = p.bmpW,
                bitmapHeight = p.bmpH,
                bitmapBytes = p.bmpBytes,
                pageTotalMs = stripShare + nfc + trust + p.renderMs + p.hiResMs + p.ocrMs,
            )
        }

        val allStages = HashMap(stages)
        val renderSum = pageProfiles.sumOf { it.renderMs + it.hiResRenderMs }
        val ocrSum = pageProfiles.sumOf { it.ocrMs }
        if (renderSum > 0) allStages[ProfilerStage.RENDER] = renderSum
        if (ocrSum > 0) allStages[ProfilerStage.PAGE_OCR] = ocrSum

        val scores = pageProfiles.mapNotNull { it.trustScore }
        val (w, h, b) = synchronized(bitmapLock) { Triple(largestW, largestH, largestBytes) }
        return DocProfile(
            docId = docId,
            displayName = displayName,
            fileType = fileType,
            origin = origin,
            status = status,
            totalMs = totalMs.toDouble(),
            stagesMs = allStages,
            pageCount = pageCount,
            trustedPages = pageProfiles.count { it.trusted == true },
            ocrFallbackPages = pageProfiles.count { it.trusted == false },
            avgTrustScore = if (scores.isEmpty()) null else scores.average(),
            largestBitmapWidth = w,
            largestBitmapHeight = h,
            largestBitmapBytes = b,
            pages = pageProfiles,
        )
    }
}

/** Process-wide toggle + bounded buffer of captured production profiles (developer diagnostics). */
object IndexingProfiler {

    /** Developer capture toggle. When true, real indexing publishes profiles to the buffer. */
    @Volatile
    var enabled: Boolean = false

    private const val MAX_DOCS = 400
    private val profiles = Collections.synchronizedList(ArrayList<DocProfile>())

    /** A fresh recorder when enabled; null otherwise (→ every pipeline hook is a `?.` no-op). */
    fun beginOrNull(docId: String, displayName: String, fileType: String): DocProfileRecorder? =
        if (enabled) DocProfileRecorder(docId, displayName, fileType) else null

    fun publish(profile: DocProfile) {
        synchronized(profiles) {
            profiles.add(profile)
            while (profiles.size > MAX_DOCS) profiles.removeAt(0)
        }
        VaultLog.d(
            "IndexingProfiler",
            "profiled ${profile.displayName} [${profile.fileType}] total=${profile.totalMs.toLong()}ms " +
                "pages=${profile.pageCount} ocrPages=${profile.ocrFallbackPages} " +
                profile.stagesMs.entries.joinToString(" ") { "${it.key}=${it.value.toLong()}ms" },
        )
    }

    fun snapshot(): List<DocProfile> = synchronized(profiles) { ArrayList(profiles) }

    fun clear() {
        synchronized(profiles) { profiles.clear() }
    }
}

/**
 * Sprint P5 — carries the PDF page number into the (page-agnostic) OCR ladder so its tier-outcome
 * attribution stays correct when multiple OCR fallback pages run concurrently. Absent on the
 * image/screenshot path, which OCRs one bitmap with no page identity.
 */
class OcrPageTag(val page: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OcrPageTag>
}

/** Time [block] into [stage] when a recorder exists; pure pass-through when null. */
suspend inline fun <T> DocProfileRecorder?.timedStage(stage: String, block: () -> T): T {
    if (this == null) return block()
    val t0 = System.nanoTime()
    try {
        return block()
    } finally {
        stageNs(stage, System.nanoTime() - t0)
    }
}
