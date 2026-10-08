package com.amar.vault.indexing

import androidx.room.withTransaction
import com.amar.vault.CanonicalReviewQueue
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import com.amar.vault.VaultMetadata

/**
 * The single owner of all indexing-path Room writes — inserts, metadata persistence,
 * review-queue upserts, chunk inserts, and the document partial-repair delete.
 *
 * Concrete (no interface — single implementation, no polymorphism), per the standing rule.
 *
 * Transaction boundaries and crash-recovery semantics are preserved verbatim from the prior
 * inline code:
 *  - [persistImageItem] writes item + metadata (idempotent delete-then-insert) + review-queue
 *    in ONE transaction (unchanged).
 *  - [persistDocument] writes a document's record and all its chunk rows in ONE transaction;
 *    [beginDocument] + [appendDocumentChunks] do the same page by page. Either way a piece is
 *    never stored without the record of the document it names.
 *  - [deletePartialByContentHash] is a delete of its own: a crash between it and the
 *    re-insert self-heals on the next idempotent retry.
 *
 * The three calls that remove a document's pieces return the ids of the rows they removed, for
 * the caller to take out of the keyword engine too. Until the engine could forget an item,
 * the pages of a document that was read again stayed in it until the app was restarted.
 */
class IndexPersister(private val db: VaultDatabase) {

    private val vaultDao get() = db.vaultDao()
    private val documentDao get() = db.vaultDocumentDao()
    private val metaDao get() = db.vaultMetadataDao()
    private val reviewDao get() = db.canonicalReviewQueueDao()

    /** Image path: item + metadata + review-queue, atomically. */
    suspend fun persistImageItem(
        item: VaultItem,
        metadata: List<VaultMetadata>,
        reviewItems: List<CanonicalReviewQueue>,
    ) {
        db.withTransaction {
            vaultDao.insert(item)
            metaDao.deleteByItemId(item.id)
            if (metadata.isNotEmpty()) metaDao.insertAll(metadata)
            for (reviewItem in reviewItems) {
                val existingReview = reviewDao.getByRawText(reviewItem.rawText)
                if (existingReview != null) {
                    reviewDao.insert(existingReview.copy(
                        frequency = existingReview.frequency + 1,
                        lastSeen = System.currentTimeMillis()
                    ))
                } else {
                    reviewDao.insert(reviewItem)
                }
            }
        }
    }

    /** Staged image path: make/refresh the row visible without touching metadata. */
    suspend fun persistImageShell(item: VaultItem) {
        vaultDao.insert(item)
    }

    /** Staged image path: replace metadata after OCR text is already committed. */
    suspend fun persistImageMetadata(
        itemId: String,
        metadata: List<VaultMetadata>,
        reviewItems: List<CanonicalReviewQueue>,
    ) {
        db.withTransaction {
            metaDao.deleteByItemId(itemId)
            if (metadata.isNotEmpty()) metaDao.insertAll(metadata)
            for (reviewItem in reviewItems) {
                val existingReview = reviewDao.getByRawText(reviewItem.rawText)
                if (existingReview != null) {
                    reviewDao.insert(existingReview.copy(
                        frequency = existingReview.frequency + 1,
                        lastSeen = System.currentTimeMillis()
                    ))
                } else {
                    reviewDao.insert(reviewItem)
                }
            }
        }
    }

    /**
     * Document path, page by page: starts the index of [document]. Whatever is stored of an
     * earlier attempt at this file, or of an earlier version of it under the same id, is
     * removed, and the document is recorded with no pieces yet. If indexing is cut short the
     * record stays, with as many pieces as were stored; reading the file again starts here
     * again. Returns the ids of the pieces removed.
     */
    suspend fun beginDocument(document: VaultDocument): List<String> =
        db.withTransaction { replaceDocument(document) }

    /** Document path, page by page: the next pieces of a document that was begun. */
    suspend fun appendDocumentChunks(documentId: String, items: List<VaultItem>) {
        db.withTransaction {
            vaultDao.insertAll(items)
            documentDao.refreshChunkCount(documentId)
        }
    }

    /**
     * Document path, all at once: the record of [document] and all its pieces, or none of it.
     * Returns the ids of the pieces that were there before and are not among [items].
     */
    suspend fun persistDocument(document: VaultDocument, items: List<VaultItem>): List<String> =
        db.withTransaction {
            val removed = replaceDocument(document)
            vaultDao.insertAll(items)
            documentDao.refreshChunkCount(document.id)
            val stored = items.mapTo(HashSet()) { it.id }
            removed.filter { it !in stored }
        }

    /** Document path: how many pages the file turned out to have. */
    suspend fun recordPageCount(documentId: String, pageCount: Int) {
        documentDao.setPageCount(documentId, pageCount)
    }

    /**
     * Document partial-repair: drop the incomplete rows, and the record of the document they
     * belonged to, before re-indexing. Returns the ids of the rows dropped.
     */
    suspend fun deletePartialByContentHash(contentHash: String): List<String> =
        db.withTransaction {
            val removed = vaultDao.chunkIdsByContentHash(contentHash)
            vaultDao.deleteChunksByContentHash(contentHash)
            documentDao.deleteByContentHash(contentHash)
            removed
        }

    private suspend fun replaceDocument(document: VaultDocument): List<String> {
        val removed = (vaultDao.chunkIdsByContentHash(document.contentHash) +
            vaultDao.chunkIdsOfDocument(document.id)).distinct()
        vaultDao.deleteChunksByContentHash(document.contentHash)
        vaultDao.deleteChunksOfDocument(document.id)
        documentDao.deleteByContentHash(document.contentHash)
        documentDao.upsert(document.copy(chunkCount = 0))
        return removed
    }
}
