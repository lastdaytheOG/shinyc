package com.amar.vault.indexing

import androidx.room.withTransaction
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import java.io.File

/**
 * Takes something out of the vault: a document with every page stored of it, or a single
 * item such as a picture. The user's own file is not touched — the vault never owned it. What
 * was shared into the app is the exception: the copy the app made of it goes too.
 *
 * [forgetKeywords] takes rows out of the keyword engine, [forgetFile] forgets that a file's
 * bytes were read (so adding it again reads it again), [forgetVectors] drops an item's vectors
 * and [deleteCopy] deletes the app's copy of a shared file; a test passes its own.
 */
class VaultRemoval(
    private val db: VaultDatabase,
    private val forgetKeywords: (List<String>) -> Unit,
    private val forgetFile: (contentHash: String) -> Unit = {},
    private val forgetVectors: (itemId: String) -> Unit = {},
    private val letGo: (address: String) -> Unit = {},
    private val deleteCopy: (address: String) -> Unit = {},
) {
    /** What was removed, for the screen to say. */
    data class Removed(val name: String, val rows: Int)

    /**
     * Removes what [row] belongs to: its whole document when it is a piece of one or the row of
     * a shared file that has been read, else the item itself. Null when it was not there any more.
     */
    suspend fun remove(row: VaultItem): Removed? {
        val id = row.parentDocumentId ?: row.id
        val isDocument = db.vaultDocumentDao().getById(id) != null || db.vaultDao().chunkIdsOfDocument(id).isNotEmpty()
        return if (isDocument) removeDocument(id) else removeItem(id)
    }

    /**
     * A document: its record, every piece of its text, and its place on the import list. A file
     * shared into the app also has a row of its own, under the document's id, and the app's copy.
     * Left behind, that row is still found by the file's name, and start-up repair reads a file
     * that has one again.
     */
    suspend fun removeDocument(documentId: String): Removed? {
        val document = db.vaultDocumentDao().getById(documentId)
        val pieces = db.vaultDao().chunkIdsOfDocument(documentId)
        val own = db.vaultDao().getByIds(listOf(documentId)).firstOrNull()
        if (document == null && pieces.isEmpty() && own == null) return null
        db.withTransaction {
            db.vaultDao().deleteChunksOfDocument(documentId)
            db.vaultDocumentDao().deleteById(documentId)
            db.documentImportDao().delete(documentId)
            if (own != null) db.vaultDao().deleteById(documentId)
        }
        forgetKeywords(pieces + listOfNotNull(own?.id))
        (document?.contentHash ?: own?.contentHash)?.takeIf { it.isNotEmpty() }?.let { hash -> runCatching { forgetFile(hash) } }
        if (document != null) {
            // Nothing opens it from there any more, unless another document does.
            if (db.vaultDocumentDao().getByUri(document.uri).isEmpty()) runCatching { letGo(document.uri) }
        }
        if (own != null) runCatching { deleteCopy(own.uri) }
        return Removed(document?.name ?: own?.title ?: "Document", pieces.size + (if (own != null) 1 else 0))
    }

    /** One whole item — a picture, a link. What hangs off it in the database goes with it. */
    suspend fun removeItem(itemId: String): Removed? {
        val item = db.vaultDao().getByIds(listOf(itemId)).firstOrNull() ?: return null
        db.withTransaction {
            db.vaultDao().deleteById(itemId)
            // What a picture looks like goes with it (photo search keeps it apart, see PhotoVector).
            db.photoVectorDao().deleteItems(listOf(itemId))
            // A shared file still waiting to be read must not be read after this.
            db.documentImportDao().delete(itemId)
        }
        forgetKeywords(listOf(itemId))
        runCatching { forgetVectors(itemId) }
        runCatching { deleteCopy(item.uri) }
        return Removed(item.title ?: item.sourceFile.ifBlank { item.itemType.stored }, 1)
    }
}

/** The copy the app keeps of a file shared into it, in `files/shared_imports`. */
internal object SharedCopy {
    /** Deletes [address] when it is such a copy. Anything else, the app does not own: it stays. */
    fun delete(filesDir: File, address: String): Boolean {
        val path = address.removePrefix("file://")
        if ("://" in path) return false
        val folder = File(filesDir, "shared_imports").canonicalFile
        val file = File(path).canonicalFile
        return file.parentFile == folder && file.delete()
    }
}

/** The words about taking something out of the vault. */
object RemovalWords {
    fun question(name: String, isDocument: Boolean): String =
        "Remove \"$name\" from your vault?"

    fun whatHappens(isDocument: Boolean, isPicture: Boolean): String = when {
        isDocument -> "Its text will no longer be found by search. The file itself stays on your phone, and you can add it again."
        isPicture -> "It will no longer be found by search. The picture itself stays on your phone."
        else -> "It will no longer be found by search."
    }
}
