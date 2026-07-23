package com.amar.vault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.amar.vault.indexing.IndexingProfiler
import com.amar.vault.indexing.ProfilerStage
import com.amar.vault.indexing.timedStage
import com.amar.vault.planning.PlannerShadowRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class IndexingPipeline private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: IndexingPipeline? = null

        fun getInstance(context: Context): IndexingPipeline {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: IndexingPipeline(context.applicationContext)
                    .also { INSTANCE = it }
            }
        }
    }

    private val db         = VaultDatabase.get(context)
    private val dao        = db.vaultDao()

    // Phase 2B stages (pure/stateless helpers held as fields; each independently testable).
    private val imageContentExtractor = com.amar.vault.indexing.ImageContentExtractor(context)
    private val duplicateDetector = com.amar.vault.indexing.DuplicateDetector(dao)
    private val metadataStage = com.amar.vault.indexing.MetadataStage()
    private val chunker: com.amar.vault.indexing.Chunker = com.amar.vault.indexing.WordWindowChunker()
    private val persister = com.amar.vault.indexing.IndexPersister(db)
    private val vectorUpdater = com.amar.vault.indexing.VectorIndexUpdater(context, VectorSearchManager.getInstance(context))
    private val stagedDeepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bm25: com.amar.vault.retrieval.Bm25Index by lazy {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
        ).bm25Index()
    }
    private val bm25Updater by lazy { com.amar.vault.indexing.Bm25IndexUpdater(bm25) }

    suspend fun indexBitmap(
        bitmap: Bitmap,
        uri: String,
        itemType: String = "screenshot",
        baseId: String? = null
    ) = withContext(Dispatchers.Default) {

        val t0 = System.currentTimeMillis()

        // Shadow-only planner observation. Disabled by default and intentionally before no
        // existing decision; it cannot alter dedup, OCR, persistence, or vector behaviour.
        PlannerShadowRegistry.observeImage(uri, itemType, bitmap.width, bitmap.height)

        val duplicateTarget = baseId?.let { dao.getByIds(listOf(it)).firstOrNull() }

        // Image dedup = perceptual hash; the pHash is also stored on the item below.
        val (hash, isDuplicate) = duplicateDetector.imageVerdict(bitmap)
        if (isDuplicate) {
            val existingByHash = duplicateTarget ?: dao.findByPHash(hash)
            if (existingByHash != null && existingByHash.ocrText.isBlank()) {
                VaultLog.w("IndexingPipeline", "Repairing staged image shell with blank OCR: ${existingByHash.id}")
            } else {
                IndexMetrics.increment(IndexMetrics.Event.INDEX_SKIPPED_DUP)
                return@withContext
            }
        }
        VaultLog.v("IndexTiming", "pHash: ${System.currentTimeMillis() - t0}ms")

        val targetId = duplicateTarget?.id ?: baseId ?: dao.findByPHash(hash)?.takeIf { it.ocrText.isBlank() }?.id ?: UUID.randomUUID().toString()
        val existing = duplicateTarget ?: dao.getByIds(listOf(targetId)).firstOrNull()
        val prof = IndexingProfiler.beginOrNull(uri, uri.substringAfterLast('/').ifBlank { uri }, itemType)

        val shellItem = existing?.copy(
            pHash = hash,
            timestamp = System.currentTimeMillis(),
        ) ?: VaultItem(
            id = targetId,
            uri = uri,
            ocrText = "",
            lang = "und",
            itemType = itemType,
            sourceFile = uri.substringAfterLast('/'),
            timestamp = System.currentTimeMillis(),
            pHash = hash,
        )
        prof.timedStage(ProfilerStage.ROOM) {
            persister.persistImageShell(shellItem)
        }
        IndexMetrics.recordDuration(IndexMetrics.Timing.INDEX_FAST_COMMIT, System.currentTimeMillis() - t0)

        val t1      = System.currentTimeMillis()

        // Sprint E3 — observation-only profiler (null unless the developer capture toggle is on).
        // OCR + QR/barcode scanning (run in parallel inside the image content-extraction stage).
        val (ocrText, qrPayloads) = imageContentExtractor.extract(bitmap, sourceId = targetId)

        val ocrMs = System.currentTimeMillis() - t1
        IndexMetrics.recordDuration(IndexMetrics.Timing.OCR, ocrMs)
        prof?.stageMs(ProfilerStage.OCR_IMAGE, ocrMs)
        VaultLog.v("IndexTiming", "OCR: ${ocrMs}ms | ${VaultLog.len(ocrText)} | QR: ${qrPayloads.size} found")

        // If both OCR and QR found nothing, keep the shell row visible/openable and stop deep work.
        if (ocrText.isBlank() && qrPayloads.isEmpty()) {
            IndexMetrics.increment(IndexMetrics.Event.INDEX_SUCCESS)
            val totalMs = System.currentTimeMillis() - t0
            IndexMetrics.recordDuration(IndexMetrics.Timing.INDEX_TOTAL, totalMs)
            prof?.let { IndexingProfiler.publish(it.build(totalMs, "success:empty_ocr")) }
            VaultLog.v("IndexTiming", "Total: ${totalMs}ms | empty OCR shell committed")
            return@withContext
        }

        // Build final text with smart tags + QR payloads
        val smartTags = generateSmartTags(ocrText)
        val qrTags = qrPayloads.map { payload ->
            "qr_data:$payload"
        }

        val allTags = mutableListOf<String>()
        if (smartTags.isNotEmpty()) allTags.add(smartTags)
        allTags.addAll(qrTags)

        // Also append QR content as searchable text so "upi" or "paytm" finds it
        val qrSearchText = qrPayloads.joinToString(" ") { payload ->
            // Extract readable parts from UPI URLs for search
            if (payload.startsWith("upi://")) {
                val params = Uri.parse(payload)
                listOfNotNull(
                    params.getQueryParameter("pn"),  // payee name
                    params.getQueryParameter("pa"),  // UPI ID
                    "upi payment qr scanner"
                ).joinToString(" ")
            } else {
                "$payload qr scanner barcode"
            }
        }

        val combinedText = if (ocrText.isNotBlank() && qrSearchText.isNotBlank()) {
            "$ocrText\n$qrSearchText"
        } else if (qrSearchText.isNotBlank()) {
            qrSearchText
        } else {
            ocrText
        }

        val tagSuffix = if (allTags.isNotEmpty()) "\n[${allTags.joinToString(" ")}]" else ""
        val finalOcrText = combinedText + tagSuffix

        val item = shellItem.copy(
            ocrText = finalOcrText,
            lang = detectLang(ocrText),
            pHash = hash,
        )

        prof.timedStage(ProfilerStage.ROOM) {
            persister.persistImageShell(item)
        }
        IndexMetrics.recordDuration(IndexMetrics.Timing.INDEX_SEARCHABLE_COMMIT, System.currentTimeMillis() - t0)

        prof.timedStage(ProfilerStage.BM25) {
            bm25Updater.update(listOf(com.amar.vault.indexing.IndexEntry(id = targetId, text = finalOcrText)))
        }
        // ── Extract metadata + classification (pure computation — no DB writes yet) ──
        val metaStartTime = System.currentTimeMillis()
        val (metadataList, reviewItemsList) = metadataStage.extract(targetId, ocrText, itemType)
        val metaTime = System.currentTimeMillis() - metaStartTime
        IndexMetrics.recordDuration(IndexMetrics.Timing.META_EXTRACT, metaTime)
        prof?.stageMs(ProfilerStage.META, metaTime)

        // ── Atomic commit (item + metadata + review-queue) via the single Room writer ──
        //    IndexPersister owns the transaction boundary; vectors are written only AFTER
        //    the commit succeeds (commit-then-index), so the native index can never point
        //    at a row that failed to persist.
        prof.timedStage(ProfilerStage.ROOM) {
            persister.persistImageMetadata(targetId, metadataList, reviewItemsList)
        }

        val t2     = System.currentTimeMillis()

        VaultLog.d("IndexingPipeline", "Index Time: ${t2-t0}ms | OCR: ${metaStartTime-t1}ms | Meta Extraction: ${metaTime}ms")
        // ── Post-commit index update (embed chunks → HNSW) via IndexUpdater ──
        val chunks = chunker.chunk(ocrText)
        val entries = chunks.mapIndexed { index, text ->
            com.amar.vault.indexing.IndexEntry(
                id          = "${targetId}_chunk${index}",
                text        = text,
                parentId    = targetId,
                chunkIndex  = index,
                totalChunks = chunks.size,
            )
        }
        if (entries.isNotEmpty()) {
            stagedDeepScope.launch {
                try {
                    vectorUpdater.update(entries)
                } catch (e: Exception) {
                    VaultLog.w("IndexingPipeline", "Deferred vector indexing failed for $targetId: ${e.message}")
                }
            }
        }

        IndexMetrics.increment(IndexMetrics.Event.INDEX_SUCCESS)
        val totalMs = System.currentTimeMillis() - t0
        IndexMetrics.recordDuration(IndexMetrics.Timing.INDEX_TOTAL, totalMs)
        prof?.let { IndexingProfiler.publish(it.build(totalMs, "success:searchable")) }
        VaultLog.v("IndexTiming", "Total: ${totalMs}ms | vector deferred")
    }

    private fun generateSmartTags(ocrText: String): String {
        val tags      = mutableListOf<String>()
        val textLower = ocrText.lowercase()
        val wordCount = ocrText.trim().split("\\s+".toRegex()).size
        val hasUrl    = textLower.contains("http") || textLower.contains("www.") || textLower.contains("upi://")
        if (wordCount <= 3 && hasUrl) tags.add("qr code scanner barcode")
        val hasAmount = textLower.contains("₹") || textLower.contains("rs") || textLower.contains("total") || textLower.contains("amount")
        if (hasAmount) tags.add("receipt payment bill invoice")
        val hasOtp = textLower.contains("otp") || textLower.contains("one time")
        if (hasOtp) tags.add("otp verification code")
        val hasPhone = Regex("[6-9]\\d{9}").containsMatchIn(ocrText)
        if (hasPhone) tags.add("contact phone number call")
        return tags.joinToString(" ")
    }

    private fun detectLang(text: String): String {
        val count = text.count { it.code in 0x0900..0x097F }
        return if (count > text.length * 0.2) "hi" else "en"
    }
}
