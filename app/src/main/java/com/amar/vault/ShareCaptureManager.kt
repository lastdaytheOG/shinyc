package com.amar.vault

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

object ShareCaptureManager {
    private const val TAG = "ShareCaptureManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun capture(
        context: Context,
        session: IngestionSession,
        attachments: List<IngestionAttachment>,
        category: String,
        userNote: String? = null
    ) {
        scope.launch {
            val db = VaultDatabase.get(context)
            val sessionDao = db.ingestionSessionDao()
            val attachmentDao = db.ingestionAttachmentDao()
            val vaultDao = db.vaultDao()
            val stashDao = db.stashItemDao()

            var captureResult: CaptureResult? = null

            try {
                // 1. Resolve Primary Content
                val resolver = ContentPriorityResolver()
                val validAttachments = attachments.filter { it.status != AttachmentStatus.FAILED }
                val failedAttachments = attachments.filter { it.status == AttachmentStatus.FAILED }

                if (validAttachments.isEmpty()) {
                    // Critical failure: No valid attachments
                    val failedSession = session.copy(status = SessionStatus.FAILED)
                    db.withTransaction {
                        sessionDao.insert(failedSession)
                        attachmentDao.insertAll(attachments)
                    }
                    CaptureTelemetry.failure(
                        sessionId = session.id,
                        stage = CaptureTelemetry.Stage.CAPTURE_FAILED,
                        reason = "No valid attachments — every shared item failed to copy",
                        recovery = "session marked FAILED, user notified"
                    )
                    CaptureFailureNotifier.notifyFailure(context, "Nothing could be read from that share.")
                    return@launch
                }

                val resolution = resolver.resolve(validAttachments)
                val primary = resolution.primaryAttachment
                // For a link, originalUri already holds the extracted URL. For a file,
                // never keep the transient content:// as the openable target — the local
                // copy (localPath) is the self-contained, permanent source.
                val isUrlPrimary = primary.attachmentType == "TEXT" &&
                    ShareUrlExtractor.containsUrl(primary.originalUri)

                val finalSessionStatus = if (failedAttachments.isNotEmpty()) SessionStatus.PARTIAL_SUCCESS else SessionStatus.READY

                var finalStashItemId: String? = null

                // 2. Transactionally save everything
                db.withTransaction {
                    // Save Session & Attachments
                    sessionDao.insert(session.copy(status = finalSessionStatus))
                    attachmentDao.insertAll(attachments.map { 
                        if (it.status == AttachmentStatus.READY) it.copy(status = AttachmentStatus.READY) else it 
                    })

                    // Deduplicate or Create VaultItem
                    val contentHash = primary.contentHash ?: UUID.randomUUID().toString()
                    var vaultItem = vaultDao.findByContentHash(contentHash)

                    val vaultItemId: String
                    if (vaultItem != null) {
                        vaultItemId = vaultItem.id
                        vaultDao.insert(vaultItem.copy(timestamp = System.currentTimeMillis()))
                        CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.DEDUP_COMPLETE, "duplicate=true vaultId=$vaultItemId")
                    } else {
                        vaultItemId = UUID.randomUUID().toString()
                        CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.DEDUP_COMPLETE, "duplicate=false vaultId=$vaultItemId")

                        val newItem = VaultItem(
                            id = vaultItemId,
                            uri = primary.localPath ?: primary.originalUri ?: "",
                            ocrText = "", // Filled in Stage 2
                            lang = "en",
                            itemType = resolution.previewType,
                            timestamp = System.currentTimeMillis(),
                            sourceFile = primary.filename ?: "",
                            contentHash = contentHash,
                            // Legacy Share Extensions
                            sourceApp = session.sourcePackage,
                            sharedAt = session.timestamp,
                            // Only a real link is a persistable openable target; a stream's
                            // content:// URI is transient, so store null and open via localPath.
                            originalUri = if (isUrlPrimary) primary.originalUri else null,
                            title = resolution.previewTitle,
                            mimeType = primary.mimeType
                        )
                        vaultDao.insert(newItem)
                    }
                    CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.VAULT_SAVED, "vaultId=$vaultItemId type=${resolution.previewType.stored}")

                    // Create StashItem
                    val stashItemId = UUID.randomUUID().toString()
                    val stashItem = StashItem(
                        id = stashItemId,
                        sessionId = session.id,
                        vaultItemId = vaultItemId,
                        category = category,
                        savedAt = System.currentTimeMillis(),
                        sourceApp = session.sourcePackage,
                        userNote = userNote
                    )
                    stashDao.insertOrUpdate(stashItem)
                    finalStashItemId = stashItemId
                    CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.STASH_SAVED, "stashId=$stashItemId category=$category")

                    captureResult = CaptureResult(
                        status = if (failedAttachments.isEmpty()) CaptureStatus.SUCCESS else CaptureStatus.PARTIAL_SUCCESS,
                        sessionId = session.id,
                        successfulAttachments = validAttachments,
                        failedAttachments = failedAttachments,
                        stashItemId = stashItemId
                    )
                }

                android.util.Log.i(TAG, "Capture transaction committed for session ${session.id}. StashId: $finalStashItemId")

                // 3. Enqueue Background Enrichment (Stage 2)
                enqueueImportWorker(context, session.id)
                CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.WORKER_QUEUED, "enrichment enqueued")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "Transaction failed for session ${session.id}", e)
                CaptureTelemetry.failure(
                    sessionId = session.id,
                    stage = CaptureTelemetry.Stage.CAPTURE_FAILED,
                    throwable = e,
                    reason = "Persistence transaction failed: ${e.message}",
                    recovery = "session/attachments marked FAILED(DATABASE_FAILED), user notified"
                )
                try {
                    val failedSession = session.copy(status = SessionStatus.FAILED)
                    val dbFailedAttachments = attachments.map { it.copy(status = AttachmentStatus.FAILED, errorCode = AttachmentError.DATABASE_FAILED) }
                    db.withTransaction {
                        sessionDao.insert(failedSession)
                        attachmentDao.insertAll(dbFailedAttachments)
                    }
                } catch (e2: Exception) {
                    android.util.Log.e(TAG, "Failed to write failure state to DB", e2)
                }
                CaptureFailureNotifier.notifyFailure(context, "Your shared item couldn't be saved.")
            }
        }
    }

    private fun enqueueImportWorker(context: Context, sessionId: String) {
        val data = Data.Builder()
            .putString("session_id", sessionId)
            .build()

        val importRequest = OneTimeWorkRequestBuilder<ShareImportWorker>()
            .setInputData(data)
            .addTag("import_session_$sessionId")
            .build()

        WorkManager.getInstance(context.applicationContext).enqueue(importRequest)
    }
}
