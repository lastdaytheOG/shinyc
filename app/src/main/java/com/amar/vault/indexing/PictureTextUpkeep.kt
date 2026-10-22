package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.amar.vault.IndexingPipeline
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog

/**
 * Reads every stored picture again with the reader as it is now, once per version of the reader.
 *
 * Version 1: pictures are no longer read with Tesseract. Measured on a phone's own gallery, it made
 * up every line stored for photos that have no text ("Pi BS", "| $ |"), added junk lines to every
 * screenshot, and was the slow part of reading. What it made up stays stored, and is found by
 * search, until the picture is read again — which is what this does. Version 2: a photo is read
 * once, not by four passes (ImageContentExtractor.readPhoto).
 *
 * Background work while the battery is not low, one picture at a time, carrying on from where it
 * stopped; the version counts as done when every picture has been read again. Each picture goes
 * through [IndexingPipeline.rereadBitmap], the indexing pipeline itself, so its text, tags, the
 * keyword engine and what is extracted from the text are brought up to date together.
 */
class PictureTextUpkeep(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return Result.success()
        val db = VaultDatabase.get(app)
        val pipeline = IndexingPipeline.getInstance(app)
        var after = prefs.getLong(KEY_AFTER, Long.MIN_VALUE)
        var read = prefs.getInt(KEY_READ, 0)
        var unreadable = prefs.getInt(KEY_UNREADABLE, 0)
        // A picture read again is written back as a new row (INSERT OR REPLACE), under a new row
        // id. The pass stops at the last row there was when it began; walking on, it met the same
        // pictures again under their new ids and never came to an end.
        val last = prefs.getLong(KEY_LAST, 0L).takeIf { it > 0 }
            ?: db.vaultDao().lastRowId().also { prefs.edit().putLong(KEY_LAST, it).apply() }
        val started = System.currentTimeMillis()
        var done = false
        while (!isStopped && !done) {
            val batch = db.vaultDao().wholeItemsForTyping(after, BATCH)
            if (batch.isEmpty()) break
            for (row in batch) {
                if (isStopped) break
                if (row.rowId > last) { done = true; break }
                if (row.itemType.isImage) {
                    val bitmap = BitmapDecoding.loadSoftware(app, Uri.parse(row.uri))
                    if (bitmap == null) {
                        unreadable++
                    } else {
                        try {
                            pipeline.rereadBitmap(bitmap, row.uri, row.id)
                            read++
                        } catch (e: Exception) {
                            VaultLog.w(TAG, "reading ${row.id} again failed: ${e.message}")
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                after = row.rowId
                prefs.edit().putLong(KEY_AFTER, after).putInt(KEY_READ, read).putInt(KEY_UNREADABLE, unreadable).apply()
            }
        }
        if (isStopped) return Result.retry()
        val summary = "reader v$VERSION; $read pictures read again in ${(System.currentTimeMillis() - started) / 1000} s" +
            if (unreadable > 0) "; $unreadable could not be opened (no longer on the phone)" else ""
        prefs.edit().putInt(KEY_VERSION, VERSION).remove(KEY_AFTER).remove(KEY_READ).remove(KEY_UNREADABLE)
            .remove(KEY_LAST).putString(KEY_REPORT, "${System.currentTimeMillis()}|$summary").apply()
        VaultLog.i(TAG, summary)
        return Result.success()
    }

    companion object {
        /** Bumped when the way pictures are read changes enough that stored text should be read again. */
        const val VERSION = 2
        private const val TAG = "PictureTextUpkeep"
        private const val WORK = "picture_text_upkeep"
        private const val PREFS = "picture_text"
        private const val KEY_VERSION = "reader_version"
        // Where a pass has got to belongs to its version: a newer reader starts from the beginning.
        private const val KEY_AFTER = "after_row_v$VERSION"
        private const val KEY_READ = "read_v$VERSION"
        private const val KEY_UNREADABLE = "unreadable_v$VERSION"
        private const val KEY_LAST = "last_row_v$VERSION"
        private const val KEY_REPORT = "last_report"
        private const val BATCH = 200

        /** Starts the pass when the stored pictures were read with an older reader. */
        fun enqueueIfNeeded(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getInt(KEY_VERSION, 0) >= VERSION) return
            val request = OneTimeWorkRequestBuilder<PictureTextUpkeep>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        /** When stored pictures were last read again and what that did; null when never. */
        fun lastReport(context: Context): Pair<Long, String>? {
            val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REPORT, null) ?: return null
            val at = saved.substringBefore('|').toLongOrNull() ?: return null
            return at to saved.substringAfter('|')
        }
    }
}
