package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amar.vault.BulkScanWorker
import com.amar.vault.IndexingPipeline
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import kotlinx.coroutines.CancellationException

class DeeperReadUpkeep(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val waiting = DeeperReads.get(app)
        val dao = VaultDatabase.get(app).vaultDao()
        val pipeline = IndexingPipeline.getInstance(app)
        val pacer = HeatPacer.of(app, HeatPacer.IN_THE_BACKGROUND_MS)
        val started = System.currentTimeMillis()
        val tried = HashSet<String>()
        var read = 0
        var gone = 0
        while (!isStopped) {
            val next = waiting.waiting().filterNot { it in tried }
            if (next.isEmpty()) break
            for (id in next) {
                if (isStopped) break
                if (BulkScanWorker.isRunning(app)) return Result.retry()
                pacer.awaitCool { !isStopped }
                if (isStopped) break
                tried += id
                val row = dao.getByIds(listOf(id)).firstOrNull()
                val bitmap = row?.takeIf { it.itemType.isImage }?.let { BitmapDecoding.loadSoftware(app, Uri.parse(it.uri)) }
                if (row == null || bitmap == null) {
                    waiting.mark(id, false)
                    gone++
                    continue
                }
                try {
                    pipeline.rereadBitmap(bitmap, row.uri, id)
                    read++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    VaultLog.w(TAG, "reading $id in full failed: ${e.message}")
                    waiting.mark(id, false)
                } finally {
                    bitmap.recycle()
                }
            }
        }
        if (isStopped) return Result.retry()
        val left = waiting.size()
        val summary = "$read screens read in full in ${(System.currentTimeMillis() - started) / 1000} s" +
            (if (gone > 0) "; $gone no longer in the vault or on the phone" else "") +
            (if (left > 0) "; $left still waiting" else "")
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REPORT, "${System.currentTimeMillis()}|$summary").apply()
        VaultLog.i(TAG, summary)
        return if (left > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "DeeperReadUpkeep"
        private const val WORK = "deeper_read_upkeep"
        private const val PREFS = "deeper_reads"
        private const val KEY_REPORT = "last_report"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DeeperReadUpkeep>()
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueIfNeeded(context: Context) {
            if (DeeperReads.get(context).size() > 0) enqueue(context)
        }

        fun lastReport(context: Context): Pair<Long, String>? {
            val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REPORT, null) ?: return null
            val at = saved.substringBefore('|').toLongOrNull() ?: return null
            return at to saved.substringAfter('|')
        }
    }
}
