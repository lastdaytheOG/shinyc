package com.amar.vault.indexing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Points a document at where its file is now.
 *
 * A document added with the file picker is not copied: the vault keeps its text and the
 * address it was chosen at, and opens it from there. Move, rename or delete the file and the
 * address leads nowhere, though every page can still be searched. Copying each file in would
 * prevent that at the cost of storing every document twice; the user chose not to pay it. So
 * when the file is gone the app says so, and the user shows it where the file is — once. The
 * file they pick is taken only if it is the very file the document was read from.
 *
 * What touches the phone is passed in, so that a test can stand in for it: [canOpen] tries an
 * address, [fingerprintOf] hashes a file's bytes, [completedAs] is the reuse cache's memory of
 * which bytes gave which text, [textHashOf] reads a file's text the way the indexer would, and
 * [keep] / [letGo] take and give back the lasting permission to read an address.
 */
class DocumentRelink(
    private val db: VaultDatabase,
    private val canOpen: (String) -> Boolean,
    private val fingerprintOf: (Uri) -> String?,
    private val completedAs: (fingerprint: String) -> String?,
    private val textHashOf: suspend (Uri, VaultDocument) -> String?,
    private val keep: (Uri) -> Unit = {},
    private val letGo: (String) -> Unit = {},
) {
    sealed interface Outcome {
        /** [document] now opens from [uri]. */
        data class Found(val document: VaultDocument, val uri: Uri) : Outcome
        /** The file picked is not the one this document was read from. */
        object AnotherFile : Outcome
        /** The file picked could not be read at all. */
        object CannotRead : Outcome
    }

    /** The documents whose file cannot be opened from where it was added. */
    suspend fun missing(): List<VaultDocument> =
        db.vaultDocumentDao().getAll().filter { !canOpen(it.uri) }

    /** The documents opened from [address] — where a viewer that failed to open it looks. */
    suspend fun documentsAt(address: String): List<VaultDocument> =
        db.vaultDocumentDao().getByUri(address)

    /** Points [document] at [picked] if that is the file it was read from. */
    suspend fun relink(document: VaultDocument, picked: Uri): Outcome {
        val fingerprint = fingerprintOf(picked) ?: return Outcome.CannotRead
        if (!isSameFile(document, picked, fingerprint)) return Outcome.AnotherFile
        moveTo(document, picked)
        return Outcome.Found(document, picked)
    }

    /**
     * Tries every one of [picked] against every one of [documents], and points each document at
     * the file that is its own. Returns the documents that found their file.
     */
    suspend fun relinkAll(documents: List<VaultDocument>, picked: List<Uri>): List<Outcome.Found> {
        val left = documents.toMutableList()
        val found = ArrayList<Outcome.Found>()
        for (uri in picked) {
            val fingerprint = fingerprintOf(uri) ?: continue
            // Two documents can have been read from copies of one file; both are pointed at it.
            val own = left.filter { isSameFile(it, uri, fingerprint) }
            for (document in own) {
                moveTo(document, uri)
                found += Outcome.Found(document, uri)
            }
            left -= own.toSet()
        }
        return found
    }

    /**
     * True when [picked], whose bytes hash to [fingerprint], is the file [document] was read
     * from. A PDF read page by page is stored under the hash of its bytes; one read whole is
     * stored under the hash of its text, and the reuse cache remembers which bytes gave that
     * text. A Word, Excel or EPUB file is read again here — that takes no OCR — and its text
     * compared.
     */
    private suspend fun isSameFile(document: VaultDocument, picked: Uri, fingerprint: String): Boolean =
        document.contentHash == fingerprint ||
            completedAs(fingerprint) == document.contentHash ||
            (document.itemType != ItemType.PDF && textHashOf(picked, document) == document.contentHash)

    private suspend fun moveTo(document: VaultDocument, picked: Uri) {
        val address = picked.toString()
        // Before the address is stored: without it the file opens now and not after a restart.
        runCatching { keep(picked) }.onFailure { VaultLog.w(TAG, "No lasting permission for $address: ${it.message}") }
        db.withTransaction {
            db.vaultDocumentDao().setUri(document.id, address)
            db.vaultDao().setUriOfDocumentChunks(document.id, address)
            db.documentImportDao().setUri(document.id, address, System.currentTimeMillis())
        }
        moved[document.uri] = address
        if (document.uri != address) runCatching { letGo(document.uri) }
        VaultLog.i(TAG, "${document.name} now opens from $address")
    }

    companion object {
        private const val TAG = "DocumentRelink"

        /**
         * Old address → new, for this run of the app. A list of search results that is on
         * screen still holds the old address of a document that was just found again.
         */
        private val moved = ConcurrentHashMap<String, String>()

        /** Where the file once at [address] is now, if it was found again since the app started. */
        fun currentAddress(address: String): String {
            var current = address
            repeat(4) { current = moved[current] ?: return current }
            return current
        }

        fun get(context: Context): DocumentRelink {
            val app = context.applicationContext
            val db = VaultDatabase.get(app)
            val extractor = DocumentContentExtractor()
            return DocumentRelink(
                db = db,
                canOpen = { address -> canOpen(app, address) },
                fingerprintOf = { uri -> PdfSourceReuseCache.fingerprint(app, uri) },
                completedAs = { fingerprint -> PdfSourceReuseCache.lookup(app, fingerprint)?.textHash },
                textHashOf = { uri, document ->
                    runCatching {
                        val mimeType = extractor.resolveMimeType(app.contentResolver.getType(uri), document.name)
                            ?: return@runCatching null
                        val pieces = extractor.extract(app, uri, mimeType).pagedChunks
                        DuplicateDetector(db.vaultDao()).documentVerdict(pieces).contentHash
                    }.getOrNull()
                },
                keep = { uri ->
                    if (uri.scheme == "content") {
                        app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                },
                letGo = { address ->
                    val old = Uri.parse(address)
                    if (old.scheme == "content") {
                        app.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                },
            )
        }

        /** True when the file at [address] can be opened for reading right now. */
        fun canOpen(context: Context, address: String): Boolean {
            val uri = runCatching { Uri.parse(address) }.getOrNull() ?: return false
            return when (uri.scheme?.lowercase()) {
                "content" -> runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                "file" -> uri.path?.let { File(it).isFile } ?: false
                null -> File(address).isFile
                // A link is not a file that can go missing.
                else -> true
            }
        }
    }
}
