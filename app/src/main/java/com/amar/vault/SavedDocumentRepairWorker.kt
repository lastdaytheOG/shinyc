package com.amar.vault

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Indexes the text of documents that were shared into the vault before sharing read them.
 *
 * Until 2026-10 the share import handed the indexer a path it could not open, so every shared
 * PDF was saved with its name and no text — and stayed that way, because nothing looked at it
 * again. This runs at app start and gives each saved document one attempt. A document that is
 * already indexed costs one read of its file (the indexer recognises it and skips the work).
 *
 * An attempt is recorded only when it finishes, so one cut short by the system is made again.
 */
@HiltWorker
class SavedDocumentRepairWorker @dagger.assisted.AssistedInject constructor(
    @dagger.assisted.Assisted context: Context,
    @dagger.assisted.Assisted params: WorkerParameters,
    private val db: VaultDatabase,
    private val documentIndexer: DocumentIndexer,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SavedDocumentRepair"
        private const val WORK_NAME = "saved_document_repair"
        private const val PREFS = "saved_document_repair"
        private const val KEY_ATTEMPTED = "attempted_item_ids"

        /** Safe to call on every launch: a run already queued or running is left alone. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SavedDocumentRepairWorker>().build(),
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val attempted = prefs.getStringSet(KEY_ATTEMPTED, emptySet()).orEmpty().toMutableSet()

        for (item in db.vaultDao().getStandaloneLocalFiles()) {
            if (item.id in attempted || !File(item.uri).isFile) continue
            // Null: not a document (a shared photo or video is a local file too).
            val result = documentIndexer.indexSavedDocument(item) ?: continue
            VaultLog.i(TAG, "${item.id}: ${result::class.simpleName}")
            attempted += item.id
            prefs.edit().putStringSet(KEY_ATTEMPTED, attempted.toSet()).apply()
        }
        Result.success()
    }
}
