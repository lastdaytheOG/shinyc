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
                val toSave = SharedFiles.toSave(resolution.primaryAttachment, validAttachments)

                val finalSessionStatus = if (failedAttachments.isNotEmpty()) SessionStatus.PARTIAL_SUCCESS else SessionStatus.READY

                var finalStashItemId: String? = null

                // 2. Transactionally save everything
                db.withTransaction {
                    // Save Session & Attachments
                    sessionDao.insert(session.copy(status = finalSessionStatus))
                    attachmentDao.insertAll(attachments.map {
                        if (it.status == AttachmentStatus.READY) it.copy(status = AttachmentStatus.READY) else it
                    })

                    // One vault item and one Saved entry for each thing that was shared. Until
                    // 2026-10 only the first was kept: of five PDFs shared together, four were
                    // copied into the app and then never seen again.
                    val firstAt = System.currentTimeMillis()
                    toSave.forEachIndexed { position, attachment ->
                        val stashItemId = saveOne(
                            db, session, attachment, resolver.resolve(listOf(attachment)), category, userNote,
                            // In the order they were shared, when the list is sorted by time.
                            at = firstAt + position,
                        )
                        if (finalStashItemId == null) finalStashItemId = stashItemId
                    }

                    captureResult = CaptureResult(
                        status = if (failedAttachments.isEmpty()) CaptureStatus.SUCCESS else CaptureStatus.PARTIAL_SUCCESS,
                        sessionId = session.id,
                        successfulAttachments = validAttachments,
                        failedAttachments = failedAttachments,
                        stashItemId = finalStashItemId
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

    /**
     * Saves one shared thing: its vault item (or the one already there with the same
     * contents) and its Saved entry. Returns the Saved entry's id. Called inside the capture's
     * transaction.
     */
    internal suspend fun saveOne(
        db: VaultDatabase,
        session: IngestionSession,
        attachment: IngestionAttachment,
        resolution: ContentResolution,
        category: String,
        userNote: String?,
        at: Long = System.currentTimeMillis(),
    ): String {
        val vaultDao = db.vaultDao()
        // For a link, originalUri already holds the extracted URL. For a file,
        // never keep the transient content:// as the openable target — the local
        // copy (localPath) is the self-contained, permanent source.
        val isUrl = attachment.attachmentType == "TEXT" &&
            ShareUrlExtractor.containsUrl(attachment.originalUri)

        // Deduplicate or Create VaultItem
        val contentHash = attachment.contentHash ?: UUID.randomUUID().toString()
        val vaultItem = vaultDao.findByContentHash(contentHash)

        val vaultItemId: String
        if (vaultItem != null) {
            vaultItemId = vaultItem.id
            vaultDao.insert(vaultItem.copy(timestamp = at))
            CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.DEDUP_COMPLETE, "duplicate=true vaultId=$vaultItemId")
        } else {
            vaultItemId = UUID.randomUUID().toString()
            CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.DEDUP_COMPLETE, "duplicate=false vaultId=$vaultItemId")

            val newItem = VaultItem(
                id = vaultItemId,
                uri = attachment.localPath ?: attachment.originalUri ?: "",
                ocrText = "", // Filled in Stage 2
                lang = "en",
                itemType = resolution.previewType,
                timestamp = at,
                sourceFile = attachment.filename ?: "",
                contentHash = contentHash,
                // Legacy Share Extensions
                sourceApp = session.sourcePackage,
                sharedAt = session.timestamp,
                // Only a real link is a persistable openable target; a stream's
                // content:// URI is transient, so store null and open via localPath.
                originalUri = if (isUrl) attachment.originalUri else null,
                title = resolution.previewTitle,
                mimeType = attachment.mimeType
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
            savedAt = at,
            sourceApp = session.sourcePackage,
            userNote = userNote
        )
        db.stashItemDao().insertOrUpdate(stashItem)
        CaptureTelemetry.stage(session.id, CaptureTelemetry.Stage.STASH_SAVED, "stashId=$stashItemId category=$category")
        return stashItemId
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

/** Which of the things in one share are saved. */
internal object SharedFiles {

    /**
     * [primary] — what the share is mostly about, as [ContentPriorityResolver] picked it — and
     * every other file that came with it, each once. Text that comes along with files (a
     * caption, a mail subject) is not a thing of its own and is saved only when it is the
     * primary.
     */
    fun toSave(primary: IngestionAttachment, valid: List<IngestionAttachment>): List<IngestionAttachment> =
        (listOf(primary) + valid.filter { it.attachmentType == "STREAM" && it.id != primary.id })
            .distinctBy { it.contentHash ?: it.id }
}
