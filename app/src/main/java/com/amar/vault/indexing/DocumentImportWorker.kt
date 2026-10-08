package com.amar.vault.indexing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amar.vault.DocumentIndexer
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads the documents that are written down in [DocumentImport], until none is left.
 *
 * Two things let it finish a file of any length. While the app is open it runs in the
 * foreground with a notification, and the system does not stop foreground work after ten
 * minutes. When it cannot (it was started again by the system with the app closed), it is
 * stopped after ten minutes like any background work — and started again, and each time it
 * carries on from the page it reached ([com.amar.vault.ReadingLog]).
 */
class DocumentImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(ImportNotices.reading(applicationContext, null, 0))

    override suspend fun doWork(): Result {
        val app = applicationContext
        val db = VaultDatabase.get(app)
        val indexer = DocumentIndexer.getInstance(app)
        val waiting = db.documentImportDao().countUnfinished()
        if (waiting == 0) return Result.success()

        ImportNotices.ensureChannels(app)
        // Refused when the app is not on screen (Android 12 and later): the work then runs as
        // ordinary background work, in ten-minute stretches.
        var inForeground = showReading(ImportNotices.reading(app, null, waiting))
        if (!inForeground) VaultLog.i(TAG, "Reading in the background")

        val startedAt = System.currentTimeMillis()
        val lastShown = AtomicLong(0)
        val reader = DocumentImportReader(db, read = { import, log ->
            indexer.indexDocument(
                uri = Uri.parse(import.uri), mimeType = import.mimeType,
                baseId = import.id, displayName = import.name, log = log,
            )
        })
        val ended = reader.readAll(readers = READERS) { row ->
            if (!inForeground || row.isFinished) return@readAll
            // A scan finishes a page every few seconds and a text PDF hundreds a second; the
            // notification is redrawn at most once a second.
            val now = System.currentTimeMillis()
            val last = lastShown.get()
            if (now - last >= 1000 && lastShown.compareAndSet(last, now)) {
                val left = db.documentImportDao().countUnfinished()
                inForeground = showReading(ImportNotices.reading(app, row, left))
            }
        }

        ImportNotices.ended(app, ended, tookMs = System.currentTimeMillis() - startedAt)
        return Result.success()
    }

    /** Puts the work in the foreground under [notification]; false when the system does not allow it. */
    private suspend fun showReading(notification: android.app.Notification): Boolean = try {
        setForeground(foregroundInfo(notification))
        true
    } catch (stopped: kotlinx.coroutines.CancellationException) {
        throw stopped
    } catch (e: Exception) {
        false
    }

    private fun foregroundInfo(notification: android.app.Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ImportNotices.READING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ImportNotices.READING_ID, notification)
        }

    companion object {
        private const val TAG = "DocumentImportWorker"
        const val WORK_NAME = "document_import"
        /** As many files at a time as the import screen read before there was a queue. */
        private const val READERS = 2

        /**
         * Sets the reading going. Called for every file added: work already running is left to
         * finish and this request runs after it, where it finds either the new file or nothing.
         */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<DocumentImportWorker>()
                // When the system stops it at ten minutes it is started again after this wait.
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

/** The notifications about reading documents: the one shown while reading, and how it ended. */
internal object ImportNotices {
    private const val READING_CHANNEL = "document_reading"
    private const val RESULT_CHANNEL = "document_results"
    const val READING_ID = 9002
    private const val RESULT_ID = 9003
    /** A reading shorter than this ends without a notification: the screen showed it happen. */
    private const val WORTH_TELLING_MS = 20_000L

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(READING_CHANNEL, "Reading documents", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the text of a document is being read." }
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, "Documents read", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Says when a long document is finished, or could not be read." }
        )
    }

    /** The notification shown while [now] is being read and [left] files are not finished. */
    fun reading(context: Context, now: DocumentImport?, left: Int): android.app.Notification {
        val pages = now?.pageCount
        val text = when {
            now == null -> if (left > 1) "$left documents" else "Starting"
            pages != null && pages > 0 -> "${now.name}: page ${now.pagesDone} of $pages"
            else -> now.name
        }
        return NotificationCompat.Builder(context, READING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (left > 1) "Reading $left documents" else "Reading a document")
            .setContentText(text)
            .setProgress(pages ?: 0, now?.pagesDone ?: 0, pages == null || pages == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()
    }

    /** Says how the files that ended in one run of the worker ended, if it is worth saying. */
    fun ended(context: Context, ended: List<DocumentImport>, tookMs: Long) {
        // A file with no words in it is in the vault under its name: listed, not announced.
        val failed = ended.filter { it.state == ImportState.FAILED && it.failure != ImportFailure.NO_TEXT }
        val read = ended.filter { it.state == ImportState.DONE }
        if (failed.isEmpty() && (read.isEmpty() || tookMs < WORTH_TELLING_MS)) return

        val (title, body) = words(failed, read)
        runCatching {
            ensureChannels(context)
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            val notification = NotificationCompat.Builder(context, RESULT_CHANNEL)
                .setSmallIcon(if (failed.isEmpty()) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(body.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build()
            NotificationManagerCompat.from(context).notify(RESULT_ID, notification)
        }.onFailure { VaultLog.w("ImportNotices", "No notification: ${it.message}") }
    }

    /** The title and the text of the notification about [failed] and [read]. */
    internal fun words(failed: List<DocumentImport>, read: List<DocumentImport>): Pair<String, String> {
        if (failed.isEmpty()) {
            val title = if (read.size == 1) "Finished reading ${read[0].name}" else "Finished reading ${read.size} documents"
            val pages = read.sumOf { it.pageCount ?: 0 }
            return title to (if (pages > 0) "$pages ${if (pages == 1) "page" else "pages"}. You can search it now." else "You can search it now.")
        }
        val first = failed[0]
        val title = if (failed.size == 1) "Could not read ${first.name}" else "${failed.size} documents could not be read"
        val body = buildString {
            failed.take(4).forEachIndexed { i, import ->
                val failure = import.failure ?: ImportFailure.OTHER
                if (i > 0) append("\n\n")
                if (failed.size > 1) append(import.name).append(": ")
                append(ImportWords.whatHappened(failure, import.failureDetail))
                append(' ').append(ImportWords.whatToDo(failure, import.origin))
            }
            if (failed.size > 4) append("\n\nand ${failed.size - 4} more. Open Import documents to see them.")
        }
        return title to body
    }

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
