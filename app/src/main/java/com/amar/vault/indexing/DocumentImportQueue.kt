package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import com.amar.vault.DocumentIndexer
import com.amar.vault.IndexResult
import com.amar.vault.ReadingLog
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import com.amar.vault.VaultLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/** A file chosen to be read: where it is, the type its provider gave, and its name if known. */
data class FileToRead(val uri: Uri, val mimeType: String, val name: String? = null)

/**
 * The one way a document gets into the vault. A file is first written down
 * ([DocumentImport]) and then read by [DocumentImportWorker], which is given a foreground
 * notification while the app is open and is started again by the system when it is not.
 *
 * Before this, three callers each read documents their own way: the import screen in a
 * coroutine that died with the app's process, the share sheet in background work that the
 * system stops after ten minutes and restarts from the first page, and a repair pass with the
 * same limit. None of them could say afterwards what had happened to a file.
 *
 * [startWorker] is what sets the reading going; a test passes its own.
 */
class DocumentImportQueue(
    private val db: VaultDatabase,
    private val startWorker: () -> Unit,
    private val nameOf: (Uri) -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.documentImportDao()

    /** Files chosen with the picker. One already waiting or being read is not added twice. */
    suspend fun addPicked(files: List<FileToRead>): List<DocumentImport> {
        val added = files.mapNotNull { file ->
            val address = file.uri.toString()
            if (dao.unfinishedAt(address) != null) return@mapNotNull null
            DocumentImport(
                id = UUID.randomUUID().toString(), uri = address,
                name = file.name?.takeIf { it.isNotBlank() } ?: nameOf(file.uri) ?: file.uri.lastPathSegment ?: "document",
                mimeType = file.mimeType, origin = ImportOrigin.PICKED, state = ImportState.WAITING, addedAt = now(),
            ).also { dao.upsert(it) }
        }
        if (added.isNotEmpty()) startWorker()
        return added
    }

    /**
     * A file that was shared in and saved as [item], read from the app's own copy at
     * [localPath]. False when it is not a kind of document the app reads (a video, a zip):
     * nothing is queued for those.
     */
    suspend fun addShared(item: VaultItem, localPath: String = item.uri): Boolean {
        val name = item.sourceFile.ifBlank { item.title.orEmpty() }
        val mimeType = DocumentIndexer.resolveMimeType(item.mimeType, name) ?: return false
        val address = Uri.fromFile(File(localPath)).toString()
        val earlier = dao.getById(item.id)
        // Shared a second time while the first reading is still going: that one carries on.
        if (earlier != null && !earlier.isFinished && earlier.uri == address) {
            startWorker()
            return true
        }
        dao.upsert(
            DocumentImport(
                id = item.id, uri = address, name = name.ifBlank { "document" }, mimeType = mimeType,
                origin = ImportOrigin.SHARED, state = ImportState.WAITING, addedAt = now(),
            )
        )
        startWorker()
        return true
    }

    /** Reads a file that failed once more, from its first page. */
    suspend fun retry(id: String) {
        if (dao.retry(id, now()) > 0) startWorker()
    }

    /** Sets the reading going again if anything is left; called when the app starts. */
    suspend fun carryOn() {
        if (dao.countUnfinished() > 0) startWorker()
    }

    companion object {
        @Volatile private var instance: DocumentImportQueue? = null

        fun get(context: Context): DocumentImportQueue = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                DocumentImportQueue(
                    db = VaultDatabase.get(app),
                    startWorker = { DocumentImportWorker.start(app) },
                    nameOf = { uri -> DisplayNames.of(app, uri) },
                ).also { instance = it }
            }
        }
    }
}

/**
 * Reads the files that are written down, one after another, and writes down how each ended.
 * No part of it knows about WorkManager: [DocumentImportWorker] runs it, and so does a test.
 *
 * [read] is the indexer; it is handed the [ReadingLog] through which a reading that was cut
 * short finds its place again.
 */
class DocumentImportReader(
    private val db: VaultDatabase,
    private val read: suspend (DocumentImport, ReadingLog) -> IndexResult,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.documentImportDao()
    private val claim = Mutex()
    private val claimed = HashSet<String>()

    /**
     * Reads until nothing is left, [readers] files at a time. [onChange] is told after every
     * change worth showing (a file begun, a page done, a file ended), with the row as it now
     * is. Returns the rows that ended during this call.
     *
     * Cancelling it stops at once and loses nothing: a file that was being read stays written
     * down as [ImportState.READING] with the last page it finished.
     */
    suspend fun readAll(readers: Int = 1, onChange: suspend (DocumentImport) -> Unit = {}): List<DocumentImport> {
        val ended = java.util.Collections.synchronizedList(ArrayList<DocumentImport>())
        coroutineScope {
            repeat(readers.coerceAtLeast(1)) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val next = nextUnclaimed() ?: break
                        try {
                            ended += readOne(next, onChange)
                        } finally {
                            claim.withLock { claimed -= next.id }
                        }
                    }
                }
            }
        }
        return ended.toList()
    }

    private suspend fun nextUnclaimed(): DocumentImport? = claim.withLock {
        dao.unfinished().firstOrNull { it.id !in claimed }?.also { claimed += it.id }
    }

    /** Reads one file and writes down how it ended. */
    suspend fun readOne(import: DocumentImport, onChange: suspend (DocumentImport) -> Unit = {}): DocumentImport {
        val id = import.id
        suspend fun changed(): DocumentImport = (dao.getById(id) ?: import).also { onChange(it) }

        val log = object : ReadingLog {
            override suspend fun pagesDone(fingerprint: String?): Int {
                val row = dao.getById(id) ?: return 0
                // Another file is at this address now: what was read of the old one is not of this one.
                if (fingerprint == null || row.fingerprint != fingerprint) return 0
                // The page after the last one done has stopped the reading every time it was
                // begun: it is left out, and the pages after it are read.
                if (row.attempts >= STARTS_WITHOUT_A_PAGE && row.pageCount != null && row.pagesDone < row.pageCount) {
                    VaultLog.w(TAG, "${import.name}: giving up on page ${row.pagesDone + 1} after ${row.attempts} starts")
                    dao.skipPage(id, now())
                    return row.pagesDone + 1
                }
                return row.pagesDone
            }

            override suspend fun reading(fingerprint: String?, pagesDone: Int, pieces: Int) {
                dao.markReading(id, fingerprint, pagesDone, pieces, now())
                changed()
            }

            override suspend fun pageCount(pages: Int) {
                dao.setPageCount(id, pages, now())
                changed()
            }

            override suspend fun pageDone(page: Int, pieces: Int) {
                dao.markPageDone(id, page, pieces, now())
                changed()
            }
        }

        val before = dao.getById(id) ?: import
        val result = if (before.attempts >= STARTS_WITHOUT_A_PAGE && before.pageCount == null) {
            // A file without pages, or one that never opened, cannot be read around.
            IndexResult.Failure(
                import.name,
                com.amar.vault.IndexError.ExtractionFailed(
                    IllegalStateException("reading it was stopped ${before.attempts} times before anything was read")
                ),
            )
        } else try {
            read(import, log)
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (e: Exception) {
            VaultLog.e(TAG, "Reading ${import.name} threw", e)
            IndexResult.Failure(import.name, com.amar.vault.IndexError.StorageFailed(e))
        }
        when (result) {
            is IndexResult.Success ->
                dao.finish(id, ImportState.DONE, result.chunkCount, null, null, now())
            is IndexResult.Duplicate ->
                dao.finish(id, ImportState.ALREADY_THERE, 0, null, null, now())
            is IndexResult.Failure -> {
                val (failure, detail) = ImportFailure.of(result.error, import.name)
                dao.finish(id, ImportState.FAILED, dao.getById(id)?.pieces ?: 0, failure, detail, now())
            }
        }
        return changed()
    }

    companion object {
        private const val TAG = "DocumentImport"
        /** How many times reading may begin and stop without finishing a page before that page is given up on. */
        const val STARTS_WITHOUT_A_PAGE = 4
    }
}

/** What a file is called, asked of whoever provides it. */
internal object DisplayNames {
    fun of(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
