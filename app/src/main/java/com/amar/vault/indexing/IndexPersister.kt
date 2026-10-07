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
     * again.
     */
    suspend fun beginDocument(document: VaultDocument) {
        db.withTransaction { replaceDocument(document) }
    }

    /** Document path, page by page: the next pieces of a document that was begun. */
    suspend fun appendDocumentChunks(documentId: String, items: List<VaultItem>) {
        db.withTransaction {
            vaultDao.insertAll(items)
            documentDao.refreshChunkCount(documentId)
        }
    }

    /** Document path, all at once: the record of [document] and all its pieces, or none of it. */
    suspend fun persistDocument(document: VaultDocument, items: List<VaultItem>) {
        db.withTransaction {
            replaceDocument(document)
            vaultDao.insertAll(items)
            documentDao.refreshChunkCount(document.id)
        }
    }

    /** Document path: how many pages the file turned out to have. */
    suspend fun recordPageCount(documentId: String, pageCount: Int) {
        documentDao.setPageCount(documentId, pageCount)
    }

    /**
     * Document partial-repair: drop the incomplete rows, and the record of the document they
     * belonged to, before re-indexing.
     */
    suspend fun deletePartialByContentHash(contentHash: String) {
        db.withTransaction {
            vaultDao.deleteChunksByContentHash(contentHash)
            documentDao.deleteByContentHash(contentHash)
        }
    }

    private suspend fun replaceDocument(document: VaultDocument) {
        vaultDao.deleteChunksByContentHash(document.contentHash)
        vaultDao.deleteChunksOfDocument(document.id)
        documentDao.deleteByContentHash(document.contentHash)
        documentDao.upsert(document.copy(chunkCount = 0))
    }
}
