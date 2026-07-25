package com.amar.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.amar.vault.indexing.DocProfileRecorder
import com.amar.vault.indexing.IndexingProfiler
import com.amar.vault.indexing.PagedChunk
import com.amar.vault.indexing.ProfilerStage
import com.amar.vault.indexing.timedStage
import com.amar.vault.planning.PlannerShadowRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ════════════════════════════════════════════════════════════════════════════════
// Public API — Result types
// ════════════════════════════════════════════════════════════════════════════════

sealed interface IndexResult {
    data class Success(val fileName: String, val chunkCount: Int, val durationMs: Long) : IndexResult
    data class Duplicate(val fileName: String, val contentHash: String) : IndexResult
    data class Failure(val fileName: String, val error: IndexError) : IndexResult
}

sealed interface IndexError {
    data class UnsupportedFormat(val mimeType: String) : IndexError
    data class ExtractionFailed(val cause: Throwable) : IndexError
    object EmptyContent : IndexError
    data class StorageFailed(val cause: Throwable) : IndexError
}

data class IndexProgress(
    val fileName: String,
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
) {
    enum class Phase { EXTRACTING, CHUNKING, STORING, DONE }
    val fraction: Float get() = if (total > 0) current.toFloat() / total else 0f
}

// ════════════════════════════════════════════════════════════════════════════════
// DocumentIndexer — BM25-only for documents, zero embedding at index time
// ════════════════════════════════════════════════════════════════════════════════

class DocumentIndexer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DocumentIndexer"

        @Volatile
        private var instance: DocumentIndexer? = null

        fun getInstance(context: Context): DocumentIndexer =
            instance ?: synchronized(this) {
                instance ?: DocumentIndexer(context.applicationContext).also { instance = it }
            }

        /**
         * Supported document mime types — sourced from the format registry so adding a new format
         * requires no change here. (Was a `Map<String, DocFamily>`; the document picker only ever
         * used its keys, and the per-family tag/itemType now live on each [FormatExtractor].)
         */
        val SUPPORTED_TYPES: Set<String> =
            com.amar.vault.indexing.DocumentContentExtractor().supportedMimeTypes
    }

    private val db by lazy { VaultDatabase.get(context) }
    private val dao by lazy { db.vaultDao() }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    // Resolved from the Hilt graph — this class is a manual singleton, so it bridges via
    // an EntryPoint rather than constructor injection. Write serialization now lives in
    // the Bm25Index owner, so the local mutex is gone.
    private val bm25: com.amar.vault.retrieval.Bm25Index by lazy {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
        ).bm25Index()
    }

    // Phase 2B stages.
    private val contentExtractor = com.amar.vault.indexing.DocumentContentExtractor()
    private val duplicateDetector by lazy { com.amar.vault.indexing.DuplicateDetector(dao) }
    private val persister by lazy { com.amar.vault.indexing.IndexPersister(db) }
    private val bm25Updater by lazy { com.amar.vault.indexing.Bm25IndexUpdater(bm25) }

    init { PDFBoxResourceLoader.init(context) }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    suspend fun indexDocument(uri: Uri, mimeType: String, baseId: String? = null): IndexResult =
        indexDocumentWithProgress(uri, mimeType, baseId).first

    suspend fun indexDocumentWithProgress(
        uri: Uri, mimeType: String, baseId: String? = null
    ): Pair<IndexResult, Flow<IndexProgress>> {
        val flow = MutableSharedFlow<IndexProgress>(replay = 1, extraBufferCapacity = 64)
        val result = withContext(Dispatchers.IO) { doIndex(uri, mimeType, flow, baseId) }
        return result to flow.asSharedFlow()
    }

    fun indexBatch(
        documents: List<Pair<Uri, String>>,
        concurrency: Int = 3,
    ): Flow<IndexResult> = channelFlow {
        val sem = Semaphore(concurrency)
        documents.forEach { (uri, mime) ->
            launch(Dispatchers.IO) { sem.withPermit { send(doIndex(uri, mime)) } }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Core pipeline — NO EMBEDDING, BM25 only
    // ════════════════════════════════════════════════════════════════════════

    private suspend fun doIndex(
        uri: Uri, mimeType: String,
        progress: MutableSharedFlow<IndexProgress>? = null,
        baseId: String? = null
    ): IndexResult {
        val fileName = resolveFileName(uri)
        val startMs = System.currentTimeMillis()

        if (!contentExtractor.isSupported(mimeType))
            return IndexResult.Failure(fileName, IndexError.UnsupportedFormat(mimeType))

        val uriKey = uri.toString()
        if (!inFlight.add(uriKey)) return IndexResult.Duplicate(fileName, "in-flight")

        // Sprint E3 — observation-only profiler; null (all hooks no-ops) unless the developer
        // capture toggle is on. Carried down into extraction as a coroutine-context element so
        // PdfFormatExtractor can attribute its existing per-page timings to this document.
        val prof = IndexingProfiler.beginOrNull(uriKey, fileName, mimeType.substringAfterLast('/'))

        try {
            // Exact-byte reuse happens before PDFBox/OCR. Room verifies every cache hit, so a
            // stale cache file or partial prior write cannot suppress normal extraction.
            val sourceFingerprint = if (mimeType == "application/pdf") {
                val fingerprintStartedAt = System.currentTimeMillis()
                try {
                    com.amar.vault.indexing.PdfSourceReuseCache.fingerprint(context, uri)
                } finally {
                    IndexMetrics.recordDuration(
                        IndexMetrics.Timing.PDF_SOURCE_FINGERPRINT,
                        System.currentTimeMillis() - fingerprintStartedAt,
                    )
                }
            } else null
            if (sourceFingerprint != null) {
                val cached = com.amar.vault.indexing.PdfSourceReuseCache.lookup(context, sourceFingerprint)
                if (cached != null && dao.countByContentHash(cached.textHash) == cached.chunkCount) {
                    IndexMetrics.increment(IndexMetrics.Event.INDEX_SKIPPED_DUP)
                    IndexMetrics.increment(IndexMetrics.Event.PDF_SOURCE_REUSE_HIT)
                    return IndexResult.Duplicate(fileName, cached.textHash)
                }
                if (cached != null) com.amar.vault.indexing.PdfSourceReuseCache.forget(context, sourceFingerprint)
            }

            // Sprint P6 — progressive PDF path: strip/OCR page by page and commit each page's
            // chunks to Room + BM25 immediately, so the document is searchable within seconds
            // (page 1 first) instead of only after the whole file finishes. PDF-only and gated;
            // anything else falls through to the legacy all-or-nothing batch path below.
            if (mimeType == "application/pdf" &&
                sourceFingerprint != null &&
                com.amar.vault.indexing.AdaptivePdfOcrControl.progressivePdfIndexing
            ) {
                return doIndexStreaming(
                    uri, mimeType, fileName, uriKey, sourceFingerprint, baseId, progress, startMs, prof,
                )
            }

            // ── 1. Extract pages (single read — no double read) ─────────
            progress?.emit(IndexProgress(fileName, IndexProgress.Phase.EXTRACTING))

            val tExtract = System.currentTimeMillis()
            val content = runCatching {
                IndexMetrics.timed(IndexMetrics.Timing.DOC_EXTRACT) {
                    if (prof == null) contentExtractor.extract(context, uri, mimeType)
                    else withContext(prof) { contentExtractor.extract(context, uri, mimeType) }
                }
            }.getOrElse {
                prof?.let { p ->
                    p.stageMs(ProfilerStage.EXTRACT, System.currentTimeMillis() - tExtract)
                    IndexingProfiler.publish(p.build(System.currentTimeMillis() - startMs, "failed:extraction"))
                }
                return IndexResult.Failure(fileName, IndexError.ExtractionFailed(it))
            }
            prof?.stageMs(ProfilerStage.EXTRACT, System.currentTimeMillis() - tExtract)
            prof?.fileType = content.itemType
            val pagedChunks = content.pagedChunks

            // Shadow-only planner observation after the legacy extractor has supplied evidence.
            // The plan is logged for replay; no worker consults it yet.
            val observedPageCount = pagedChunks.mapNotNull { it.pdfPage }.distinct().size
                .takeIf { it > 0 } ?: 1
            PlannerShadowRegistry.observeDocument(
                sourceId = uriKey,
                mimeType = mimeType,
                pageCount = observedPageCount,
                nonEmptyPages = pagedChunks.mapNotNull { it.pdfPage }.distinct().size,
            )

            if (pagedChunks.isEmpty()) return IndexResult.Failure(fileName, IndexError.EmptyContent)

            // ── 2. Content-hash + idempotent dedup / partial-repair ─────
            //    DuplicateDetector decides (read-only + hashing); the repair *delete* stays a
            //    write owned by IndexPersister. Idempotent: a crash between the repair delete
            //    and the re-insert self-heals on the next run.
            val verdict = prof.timedStage(ProfilerStage.DEDUP) {
                IndexMetrics.timed(IndexMetrics.Timing.DOC_DEDUP) {
                    duplicateDetector.documentVerdict(pagedChunks)
                }
            }
            val contentHash = verdict.contentHash
            when (verdict) {
                is com.amar.vault.indexing.DuplicateDetector.DocVerdict.Duplicate -> {
                    VaultLog.d(TAG, "Skipping complete duplicate: $fileName")
                    return IndexResult.Duplicate(fileName, contentHash)
                }
                is com.amar.vault.indexing.DuplicateDetector.DocVerdict.Partial -> {
                    VaultLog.w(TAG, "Repairing partial index (${verdict.existingCount}/${verdict.expectedCount} chunks): $fileName")
                    IndexMetrics.increment(IndexMetrics.Event.DOC_PARTIAL_REPAIR)
                    persister.deletePartialByContentHash(contentHash)
                }
                is com.amar.vault.indexing.DuplicateDetector.DocVerdict.New -> {
                    // No prior rows for this content — index fresh.
                }
            }

            // ── 3. Build chunk rows (pure), commit atomically, then index ───
            progress?.emit(IndexProgress(fileName, IndexProgress.Phase.STORING))
            val targetId = baseId ?: UUID.randomUUID().toString()

            val chunkItems = pagedChunks.map { pagedChunk ->
                val tags = TagEngine.generate(pagedChunk.text, content.tag)
                val tagSuffix = if (tags.isNotEmpty()) "\n[${tags.joinToString(" ")}]" else ""
                val finalText = pagedChunk.text + tagSuffix
                val item = VaultItem(
                    id          = "${targetId}_chunk${pagedChunk.chunkIndex}",
                    uri         = uriKey,
                    ocrText     = finalText,
                    lang        = LanguageDetector.detect(pagedChunk.text),
                    itemType    = content.itemType,
                    pageNum     = pagedChunk.pdfPage ?: pagedChunk.chunkIndex,
                    sourceFile  = fileName,
                    timestamp   = System.currentTimeMillis(),
                    pHash       = 0L,
                    contentHash = contentHash,
                    // Explicit ownership (Task 2) — the id string is a key, not a schema.
                    parentDocumentId = targetId,
                    chunkIndex       = pagedChunk.chunkIndex,
                    totalChunks      = pagedChunks.size,
                )
                item to finalText
            }

            // Atomic: all chunk rows persist or none do — via the single Room writer.
            prof.timedStage(ProfilerStage.ROOM) {
                persister.persistDocumentChunks(chunkItems.map { it.first })
            }

            // commit-then-index: feed BM25 only after the rows are durably committed.
            // BM25 addDocument is idempotent (re-indexes an existing id), so repairs are safe.
            // No embedding — documents use BM25 + substring search (embedding is query-time only).
            val entries = chunkItems.map { (item, text) ->
                com.amar.vault.indexing.IndexEntry(
                    id = item.id, text = text,
                    parentId = targetId, chunkIndex = item.chunkIndex, totalChunks = item.totalChunks,
                )
            }
            prof.timedStage(ProfilerStage.BM25) {
                bm25Updater.update(entries) { idx ->
                    progress?.emit(IndexProgress(fileName, IndexProgress.Phase.STORING, idx + 1, pagedChunks.size))
                }
            }

            if (sourceFingerprint != null) {
                com.amar.vault.indexing.PdfSourceReuseCache.remember(
                    context = context,
                    sourceHash = sourceFingerprint,
                    textHash = contentHash,
                    chunkCount = pagedChunks.size,
                )
            }

            progress?.emit(IndexProgress(fileName, IndexProgress.Phase.DONE,
                pagedChunks.size, pagedChunks.size))

            val elapsed = System.currentTimeMillis() - startMs
            IndexMetrics.recordDuration(IndexMetrics.Timing.DOC_INDEX_TOTAL, elapsed)
            prof?.let { IndexingProfiler.publish(it.build(elapsed, "success")) }
            Log.d(TAG, "Indexed $fileName: ${pagedChunks.size} chunks in ${elapsed}ms (BM25 only, no embedding)")
            return IndexResult.Success(fileName, pagedChunks.size, elapsed)

        } catch (ce: CancellationException) { throw ce }
        catch (e: Exception) {
            Log.e(TAG, "Failed to index $fileName", e)
            prof?.let { IndexingProfiler.publish(it.build(System.currentTimeMillis() - startMs, "failed:storage")) }
            return IndexResult.Failure(fileName, IndexError.StorageFailed(e))
        } finally {
            inFlight.remove(uriKey)
        }
    }

    /**
     * Sprint P6 — progressive PDF indexing. Commits each page's chunks to Room + BM25 as they are
     * produced (see [com.amar.vault.indexing.PdfFormatExtractor.extractStreaming]) so the document
     * is searchable page-by-page within seconds instead of only after the whole file finishes.
     *
     * Identity/dedup uses the source-byte [sourceFingerprint] (computed before extraction) as every
     * row's contentHash — a stable per-file key available up front, which is what makes streaming
     * possible (the legacy text-SHA needs the entire document first). A complete re-add was already
     * skipped by the reuse-cache pre-check in [doIndex]; here we delete any prior rows for this
     * exact source (a crashed partial index) and stream fresh. Rerun self-heals: on failure the
     * fingerprint is never cached as complete, so the next run deletes the partial rows and retries.
     */
    private suspend fun doIndexStreaming(
        uri: Uri,
        mimeType: String,
        fileName: String,
        uriKey: String,
        sourceFingerprint: String,
        baseId: String?,
        progress: MutableSharedFlow<IndexProgress>?,
        startMs: Long,
        prof: DocProfileRecorder?,
    ): IndexResult {
        val descriptor = contentExtractor.descriptorFor(mimeType)
            ?: return IndexResult.Failure(fileName, IndexError.UnsupportedFormat(mimeType))
        val targetId = baseId ?: UUID.randomUUID().toString()
        prof?.fileType = descriptor.itemType

        // Clear any prior (partial) rows for this exact source before streaming fresh.
        persister.deletePartialByContentHash(sourceFingerprint)

        progress?.emit(IndexProgress(fileName, IndexProgress.Phase.EXTRACTING))
        var committed = 0
        val tExtract = System.currentTimeMillis()
        try {
            // Observation-only: carry the Sprint E3 profiler down into extractStreaming as a
            // coroutine-context element (exactly as the batch path does), so PdfFormatExtractor
            // attributes its per-page/sub-stage timings to this document. Null → plain call.
            IndexMetrics.timed(IndexMetrics.Timing.DOC_EXTRACT) {
                val onBatch: suspend (List<PagedChunk>) -> Unit = { pageChunks ->
                    val rows = pageChunks.map { pc ->
                        val tags = TagEngine.generate(pc.text, descriptor.tag)
                        val tagSuffix = if (tags.isNotEmpty()) "\n[${tags.joinToString(" ")}]" else ""
                        val finalText = pc.text + tagSuffix
                        val item = VaultItem(
                            id          = "${targetId}_chunk${pc.chunkIndex}",
                            uri         = uriKey,
                            ocrText     = finalText,
                            lang        = LanguageDetector.detect(pc.text),
                            itemType    = descriptor.itemType,
                            pageNum     = pc.pdfPage ?: pc.chunkIndex,
                            sourceFile  = fileName,
                            timestamp   = System.currentTimeMillis(),
                            pHash       = 0L,
                            contentHash = sourceFingerprint,
                            parentDocumentId = targetId,
                            chunkIndex       = pc.chunkIndex,
                            totalChunks      = 0, // unknown while streaming; informational only for docs
                        )
                        item to finalText
                    }
                    // Commit this page: Room rows (→ FTS searchable) then BM25 (→ lexical searchable).
                    persister.persistDocumentChunks(rows.map { it.first })
                    bm25Updater.update(
                        rows.map { (item, text) ->
                            com.amar.vault.indexing.IndexEntry(
                                id = item.id, text = text,
                                parentId = targetId, chunkIndex = item.chunkIndex, totalChunks = 0,
                            )
                        }
                    )
                    committed += rows.size
                    progress?.emit(IndexProgress(fileName, IndexProgress.Phase.STORING, committed, committed))
                }
                if (prof == null) contentExtractor.extractStreaming(context, uri, mimeType, onBatch)
                else withContext(prof) { contentExtractor.extractStreaming(context, uri, mimeType, onBatch) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            prof?.let { p ->
                p.stageMs(ProfilerStage.EXTRACT, System.currentTimeMillis() - tExtract)
                IndexingProfiler.publish(p.build(System.currentTimeMillis() - startMs, "failed:extraction"))
            }
            Log.e(TAG, "Progressive index failed for $fileName after $committed chunk(s)", e)
            return IndexResult.Failure(fileName, IndexError.ExtractionFailed(e))
        }
        prof?.stageMs(ProfilerStage.EXTRACT, System.currentTimeMillis() - tExtract)

        if (committed == 0) return IndexResult.Failure(fileName, IndexError.EmptyContent)

        // Mark complete for exact-source reuse (every row's contentHash == this fingerprint).
        com.amar.vault.indexing.PdfSourceReuseCache.remember(
            context = context,
            sourceHash = sourceFingerprint,
            textHash = sourceFingerprint,
            chunkCount = committed,
        )
        progress?.emit(IndexProgress(fileName, IndexProgress.Phase.DONE, committed, committed))

        val elapsed = System.currentTimeMillis() - startMs
        IndexMetrics.recordDuration(IndexMetrics.Timing.DOC_INDEX_TOTAL, elapsed)
        prof?.let { IndexingProfiler.publish(it.build(elapsed, "success")) }
        Log.d(TAG, "Progressively indexed $fileName: $committed chunks in ${elapsed}ms (searchable page-by-page)")
        return IndexResult.Success(fileName, committed, elapsed)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Chunk-row building helpers — Tags, Language, Utilities
    // (Text extraction now lives in com.amar.vault.indexing.DocumentContentExtractor
    //  and the per-format com.amar.vault.indexing.FormatExtractor registry.)
    // ════════════════════════════════════════════════════════════════════════

    object TagEngine {
        private val CONTENT_RULES = listOf(
            listOf("invoice", "bill", "receipt") to listOf("invoice", "billing", "receipt"),
            listOf("contract", "agreement", "terms and conditions") to listOf("contract", "agreement", "legal"),
            listOf("₹", "$", "€", "amount", "total", "subtotal", "payment") to listOf("financial", "payment", "monetary"),
            listOf("resume", "curriculum vitae", "cv", "work experience") to listOf("resume", "cv", "career"),
            listOf("confidential", "private", "restricted") to listOf("confidential", "sensitive"),
            listOf("meeting", "minutes", "agenda", "attendees") to listOf("meeting", "minutes", "notes"),
            listOf("report", "analysis", "findings", "summary") to listOf("report", "analysis"),
            listOf("prescription", "diagnosis", "patient", "mg", "dosage") to listOf("medical", "health"),
            listOf("marks", "grade", "semester", "exam", "cgpa", "gpa") to listOf("academic", "education"),
            listOf("tax", "gst", "pan", "itr", "tds") to listOf("tax", "government"),
        )
        fun generate(text: String, familyTag: String): List<String> {
            val lower = text.lowercase(); val tags = mutableListOf(familyTag)
            for ((kw, et) in CONTENT_RULES) { if (kw.any { it in lower }) tags.addAll(et) }
            return tags.distinct()
        }
    }

    object LanguageDetector {
        private data class Script(val code: String, val range: IntRange)
        private val SCRIPTS = listOf(Script("hi",0x0900..0x097F),Script("bn",0x0980..0x09FF),Script("ta",0x0B80..0x0BFF),Script("te",0x0C00..0x0C7F),Script("kn",0x0C80..0x0CFF),Script("ml",0x0D00..0x0D7F),Script("gu",0x0A80..0x0AFF),Script("pa",0x0A00..0x0A7F),Script("or",0x0B00..0x0B7F),Script("ar",0x0600..0x06FF),Script("zh",0x4E00..0x9FFF),Script("ja",0x3040..0x30FF),Script("ko",0xAC00..0xD7AF),Script("th",0x0E00..0x0E7F))
        fun detect(text: String): String {
            if (text.isEmpty()) return "en"
            val counts = mutableMapOf<String, Int>()
            for (ch in text) { for (s in SCRIPTS) { if (ch.code in s.range) { counts[s.code] = (counts[s.code] ?: 0) + 1; break } } }
            val dom = counts.maxByOrNull { it.value } ?: return "en"
            return if (dom.value >= text.length * 0.15) dom.key else "en"
        }
    }

    private fun resolveFileName(uri: Uri) = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (col >= 0 && c.moveToFirst()) c.getString(col) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "document"
}
