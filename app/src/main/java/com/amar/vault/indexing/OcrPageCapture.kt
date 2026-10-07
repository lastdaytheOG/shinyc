package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import com.amar.vault.VaultLog
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * NPU-OCR work-order step 4 — dev-only capture of the exact page bitmaps the OCR path receives.
 *
 * Produces **C1**, the INT8 post-training-quantization calibration corpus
 * (`docs/NPU_OCR_ASSET_GUIDE.md` §5.1). Calibration observes activation ranges over a real input
 * distribution, so the pixels it sees must be the pixels the app actually OCRs.
 *
 * ## Why capture on-device instead of re-rendering the PDFs on desktop
 *
 * Re-rendering the same PDFs with PyMuPDF/pdftoppm yields *similar but not identical* pixels —
 * different rasterizer, different anti-aliasing, different subpixel placement. For PTQ that is
 * usually tolerable, but it plants a confound: when an accuracy gap shows up later, you cannot
 * tell whether the model is wrong or the calibration inputs simply were not what the app feeds.
 * Capturing here removes that whole class of bug for the cost of one file write.
 *
 * ## Invariants
 *
 * - **PNG, always.** Lossless is the entire point. JPEG artifacts would change the pixel values
 *   that calibration measures, so the quantized model would be tuned for images the app never
 *   produces. Never "optimize" this to JPEG to save space — lower the page cap instead.
 * - **Bitmap captured as received**, before any grayscale/invert/upscale preprocessing, because
 *   that is the tensor the detector will be fed.
 * - **Disabled by default.** When [enabled] is false, [capture] returns before touching the
 *   bitmap or the filesystem — a pure pass-through with no measurable cost.
 * - **Never fails the caller.** Any IO error is counted and logged, never thrown: a developer
 *   capture session must not be able to break a user's document import.
 * - **PDF page path only.** Hooked into [ImageContentExtractor.extractPdfPageText]. The
 *   image/screenshot path is a different input distribution, and the benchmark path
 *   (`extractInstrumented`) is deliberately *not* hooked so a golden-set run can never
 *   contaminate the calibration corpus with synthetic images.
 *
 * ## Privacy
 *
 * These files are rendered pages of the user's own documents. They live in app-private
 * `filesDir/ocr-capture/`, which `BACKUP_AND_PRIVACY.md` excludes from cloud backup and device
 * transfer along with everything else in `filesDir`. Nothing is uploaded. [clear] deletes them.
 *
 * ## Pulling the corpus
 *
 * ```
 * adb shell run-as com.amar.vault tar c files/ocr-capture | tar x
 * ```
 * then move the PNGs into `tools/ocr-workbench/data/calib/pages/`.
 */
object OcrPageCapture {

    const val DIR_NAME = "ocr-capture"
    const val INDEX_FILE = "capture_index.tsv"
    private const val TAG = "OcrPageCapture"

    private const val INDEX_HEADER = "file\twidth\theight\tmegapixels\tsourceId\tcapturedAtMs"

    /**
     * Dev-only master switch. Deliberately separate from [OcrInstrumentation.enabled]: that one
     * buffers reports in memory, this one writes hundreds of megabytes of document pixels to disk.
     * Conflating them would mean anyone enabling contribution stats silently starts filling
     * storage with page images.
     */
    @Volatile
    var enabled: Boolean = false

    /**
     * Hard stop on captured pages. The asset guide calls for 200–500 pages; beyond that the extra
     * images add no calibration signal and only consume storage.
     */
    @Volatile
    var maxPages: Int = 500

    /** Hard stop on total bytes, as a second guard for unusually large pages. */
    @Volatile
    var maxBytes: Long = 400L * 1024 * 1024

    private val captured = AtomicInteger(0)
    private val skippedAtCap = AtomicInteger(0)
    private val failed = AtomicInteger(0)
    private val bytes = AtomicLong(0)

    @Volatile private var initializedFor: String? = null
    private val lock = Any()

    data class Stats(
        val enabled: Boolean,
        val captured: Int,
        val skippedAtCap: Int,
        val failed: Int,
        val bytes: Long,
        val maxPages: Int,
    ) {
        val atCap: Boolean get() = captured >= maxPages
        val megabytes: Double get() = bytes / (1024.0 * 1024.0)
    }

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * Re-reads the capture directory and returns fresh stats.
     *
     * Counters are in-memory, so after a process restart they read zero while the previous
     * session's files are still on disk. A UI that reported "0 captured" over 300 existing pages
     * would invite someone to keep capturing past the cap, or to conclude the hook is broken.
     * The dashboard calls this on open and on Refresh.
     */
    fun refreshFromDisk(context: Context): Stats {
        synchronized(lock) { initializedFor = null }
        ensureInitialized(dir(context))
        return stats()
    }

    fun stats(): Stats = Stats(
        enabled = enabled,
        captured = captured.get(),
        skippedAtCap = skippedAtCap.get(),
        failed = failed.get(),
        bytes = bytes.get(),
        maxPages = maxPages,
    )

    /**
     * Write [bitmap] to the capture directory as lossless PNG. No-op unless [enabled].
     *
     * Called from the OCR path, which may run pages concurrently, so index allocation and the
     * index-file append are serialized. Returns the written file, or null when disabled, capped,
     * or on failure — the caller ignores the result and continues either way.
     */
    fun capture(context: Context, bitmap: Bitmap, sourceId: String?): File? {
        if (!enabled) return null
        if (bitmap.isRecycled) return null

        return try {
            val dir = dir(context)
            ensureInitialized(dir)

            if (captured.get() >= maxPages || bytes.get() >= maxBytes) {
                skippedAtCap.incrementAndGet()
                return null
            }

            val file: File
            val index: Int
            synchronized(lock) {
                // Re-check inside the lock so two concurrent pages cannot both pass the cap test
                // and push the corpus one page over the limit.
                if (captured.get() >= maxPages || bytes.get() >= maxBytes) {
                    skippedAtCap.incrementAndGet()
                    return null
                }
                index = nextIndex++
                file = File(dir, fileNameFor(index, sourceId))
            }

            file.outputStream().use { out ->
                // PNG + quality 100. PNG ignores the quality argument, but passing 100 documents
                // the intent: nothing about this file may be lossy.
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IllegalStateException("Bitmap.compress returned false")
                }
            }

            val size = file.length()
            bytes.addAndGet(size)
            val n = captured.incrementAndGet()
            appendIndexRow(dir, file.name, bitmap.width, bitmap.height, sourceId)

            if (n == maxPages) {
                VaultLog.w(TAG, "page cap reached ($maxPages) — further pages skipped; pull and Clear to continue")
            }
            file
        } catch (e: Exception) {
            // Never propagate: a dev capture must not be able to fail a user's import.
            failed.incrementAndGet()
            VaultLog.w(TAG, "capture failed for sourceId=$sourceId: ${e.message}")
            null
        }
    }

    /** Deletes every captured file and resets counters. */
    fun clear(context: Context) {
        synchronized(lock) {
            val dir = dir(context)
            dir.listFiles()?.forEach { it.delete() }
            captured.set(0)
            skippedAtCap.set(0)
            failed.set(0)
            bytes.set(0)
            nextIndex = 1
            initializedFor = dir.absolutePath
        }
    }

    // ── Index allocation ────────────────────────────────────────────────────────

    private var nextIndex: Int = 1

    /**
     * Adopt whatever is already on disk before writing anything.
     *
     * Counters live in memory, so after an app restart they read zero while the previous
     * session's files are still there. Without this, the next capture would restart at index 1
     * and silently overwrite the corpus already collected — and the byte cap would be computed
     * against 0 bytes used.
     */
    private fun ensureInitialized(dir: File) {
        if (initializedFor == dir.absolutePath) return
        synchronized(lock) {
            if (initializedFor == dir.absolutePath) return
            if (!dir.exists()) dir.mkdirs()
            var maxIndex = 0
            var count = 0
            var total = 0L
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".png")) {
                    count++
                    total += f.length()
                    parseIndex(f.name)?.let { if (it > maxIndex) maxIndex = it }
                }
            }
            nextIndex = maxIndex + 1
            captured.set(count)
            bytes.set(total)
            initializedFor = dir.absolutePath
            if (count > 0) {
                VaultLog.d(TAG, "adopted $count existing captures (${total / 1024 / 1024} MB), resuming at index $nextIndex")
            }
        }
    }

    private fun appendIndexRow(dir: File, name: String, width: Int, height: Int, sourceId: String?) {
        val index = File(dir, INDEX_FILE)
        synchronized(lock) {
            if (!index.exists()) index.appendText(INDEX_HEADER + "\n")
            val mp = OcrPerfGuards.megapixels(width, height)
            index.appendText(
                "$name\t$width\t$height\t${"%.3f".format(java.util.Locale.US, mp)}\t" +
                    "${sanitizeSourceId(sourceId)}\t${System.currentTimeMillis()}\n"
            )
        }
    }

    // ── Pure helpers (unit-tested) ──────────────────────────────────────────────

    /**
     * `page_00001_<source>.png`. Zero-padded so lexical order matches capture order, which is
     * what every desktop tool in the workbench will sort by.
     */
    fun fileNameFor(index: Int, sourceId: String?): String =
        "page_%05d_%s.png".format(java.util.Locale.US, index, sanitizeSourceId(sourceId))

    /**
     * Source ids can be document ids, file paths, or synthesized labels like
     * `pdfpage@1234`. Anything that is not filename-safe becomes `_`, so a path separator or a
     * colon cannot escape the capture directory or produce an unopenable file on any host the
     * corpus is later copied to.
     */
    fun sanitizeSourceId(sourceId: String?): String {
        val raw = sourceId?.trim().orEmpty()
        if (raw.isEmpty()) return "unknown"
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '.') ch else '_')
            }
        }.trim('_', '.')
        return cleaned.ifEmpty { "unknown" }.take(48)
    }

    /** Recovers the numeric index from a capture filename, or null if it is not one. */
    fun parseIndex(fileName: String): Int? {
        if (!fileName.startsWith("page_")) return null
        val rest = fileName.removePrefix("page_")
        val digits = rest.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        return digits.toIntOrNull()
    }
}
