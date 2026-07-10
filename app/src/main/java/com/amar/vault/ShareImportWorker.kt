package com.amar.vault

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@HiltWorker
class ShareImportWorker @dagger.assisted.AssistedInject constructor(
    @dagger.assisted.Assisted context: Context,
    @dagger.assisted.Assisted params: WorkerParameters,
    private val db: VaultDatabase,
    private val indexingPipeline: IndexingPipeline,
    private val documentIndexer: DocumentIndexer,
    private val bm25: com.amar.vault.retrieval.Bm25Index,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getString("session_id") ?: return@withContext Result.failure()

        val sessionDao = db.ingestionSessionDao()
        val attachmentDao = db.ingestionAttachmentDao()
        val vaultDao = db.vaultDao()

        val session = sessionDao.getById(sessionId) ?: return@withContext Result.failure()
        val attachments = attachmentDao.getAttachmentsForSession(sessionId)

        sessionDao.updateStatus(sessionId, SessionStatus.ENRICHING)

        var allEnriched = true

        for (attachment in attachments) {
            if (attachment.status != AttachmentStatus.READY) {
                continue
            }

            attachmentDao.updateStatus(attachment.id, AttachmentStatus.ENRICHING)

            try {
                // Find matching VaultItem (the primary one the UI cares about)
                val vaultItem = attachment.contentHash?.let { vaultDao.findByContentHash(it) }

                if (vaultItem != null) {
                    val mime = attachment.mimeType.lowercase()
                    val uriStr = attachment.localPath ?: attachment.originalUri ?: ""
                    
                    if (mime.startsWith("image/")) {
                        val file = File(attachment.localPath ?: "")
                        if (file.exists()) {
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                            if (bitmap != null) {
                                indexingPipeline.indexBitmap(
                                    bitmap = bitmap,
                                    uri = uriStr,
                                    itemType = vaultItem.itemType.lowercase(),
                                    baseId = vaultItem.id
                                )
                            }
                        }
                    } else if (mime.contains("pdf") || mime.contains("document")) {
                        val uri = Uri.parse(uriStr)
                        documentIndexer.indexDocument(
                            uri = uri,
                            mimeType = mime,
                            baseId = vaultItem.id
                        )
                    } else if (attachment.attachmentType == "TEXT") {
                        val docText = "${vaultItem.title ?: ""} ${vaultItem.ocrText} ${vaultItem.sourceFile} ${vaultItem.itemType}"
                        bm25.addDocument(vaultItem.id, docText)
                    }
                    // Videos, audio, general files skip heavy OCR/indexing for now, but are "COMPLETE"

                    // Try fetching thumbnail if it's a URL and we have a stash item for it
                    val stashItem = db.stashItemDao().getByVaultItemId(vaultItem.id)
                    if (stashItem != null && stashItem.thumbnailPath == null) {
                        val thumbPath = ThumbnailFetcher.fetchThumbnail(applicationContext, uriStr, stashItem.id)
                        if (thumbPath != null) {
                            db.stashItemDao().updateThumbnailPath(stashItem.id, thumbPath)
                        }
                    }
                }

                attachmentDao.updateStatus(attachment.id, AttachmentStatus.COMPLETE)

            } catch (e: Exception) {
                CaptureTelemetry.failure(
                    sessionId = sessionId,
                    stage = CaptureTelemetry.Stage.ENRICH_FAILED,
                    mime = attachment.mimeType,
                    uri = attachment.localPath ?: attachment.originalUri,
                    throwable = e,
                    reason = "Stage-2 enrichment failed for attachment ${attachment.id}",
                    recovery = "attachment marked FAILED; Stage-1 saved item remains fully usable"
                )
                attachmentDao.updateStatus(attachment.id, AttachmentStatus.FAILED)
                allEnriched = false
            }
        }

        if (allEnriched) {
            sessionDao.updateStatus(sessionId, SessionStatus.COMPLETE)
            CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.WORKER_COMPLETE, "enrichment complete")
            Result.success()
        } else {
            sessionDao.updateStatus(sessionId, SessionStatus.PARTIAL_SUCCESS)
            CaptureTelemetry.stage(sessionId, CaptureTelemetry.Stage.ENRICH_COMPLETE, "partial: some attachments failed enrichment")
            Result.success() // Worker succeeded in running, but some attachments failed enrichment
        }
    }
}
