package com.amar.vault.indexing

import androidx.room.withTransaction
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem

/**
 * Takes something out of the vault: a document with every page stored of it, or a single
 * item such as a picture. The file on the phone is not touched — the vault never owned it.
 *
 * Until this there was no way to remove a document at all. The details screen had a "Delete
 * Item" button for every result, and for anything that was not a Saved entry it did nothing.
 *
 * [forgetKeywords] takes rows out of the keyword engine, [forgetFile] forgets that a file's
 * bytes were read (so adding it again reads it again) and [forgetVectors] drops an item's
 * vectors; a test passes its own.
 */
class VaultRemoval(
    private val db: VaultDatabase,
    private val forgetKeywords: (List<String>) -> Unit,
    private val forgetFile: (contentHash: String) -> Unit = {},
    private val forgetVectors: (itemId: String) -> Unit = {},
    private val letGo: (address: String) -> Unit = {},
) {
    /** What was removed, for the screen to say. */
    data class Removed(val name: String, val rows: Int)

    /**
     * Removes what [row] belongs to: its whole document when it is a piece of one, else the
     * item itself. Null when it was not there any more.
     */
    suspend fun remove(row: VaultItem): Removed? =
        if (row.parentDocumentId != null) removeDocument(row.parentDocumentId) else removeItem(row.id)

    /** A document: its record, every piece of its text, and its place on the import list. */
    suspend fun removeDocument(documentId: String): Removed? {
        val document = db.vaultDocumentDao().getById(documentId)
        val pieces = db.vaultDao().chunkIdsOfDocument(documentId)
        if (document == null && pieces.isEmpty()) return null
        db.withTransaction {
            db.vaultDao().deleteChunksOfDocument(documentId)
            db.vaultDocumentDao().deleteById(documentId)
            db.documentImportDao().delete(documentId)
        }
        forgetKeywords(pieces)
        if (document != null) {
            runCatching { forgetFile(document.contentHash) }
            // Nothing opens it from there any more, unless another document does.
            if (db.vaultDocumentDao().getByUri(document.uri).isEmpty()) runCatching { letGo(document.uri) }
        }
        return Removed(document?.name ?: "Document", pieces.size)
    }

    /** One whole item — a picture, a link. What hangs off it in the database goes with it. */
    suspend fun removeItem(itemId: String): Removed? {
        val item = db.vaultDao().getByIds(listOf(itemId)).firstOrNull() ?: return null
        db.vaultDao().deleteById(itemId)
        forgetKeywords(listOf(itemId))
        runCatching { forgetVectors(itemId) }
        return Removed(item.title ?: item.sourceFile.ifBlank { item.itemType.stored }, 1)
    }
}

/** The words about taking something out of the vault. */
object RemovalWords {
    fun question(name: String, isDocument: Boolean): String =
        "Remove \"$name\" from your vault?"

    fun whatHappens(isDocument: Boolean): String =
        if (isDocument) "Its text will no longer be found by search. The file itself stays on your phone, and you can add it again."
        else "It will no longer be found by search. The picture itself stays on your phone."
}
