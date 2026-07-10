package com.amar.vault.indexing

import androidx.room.withTransaction
import com.amar.vault.CanonicalReviewQueue
import com.amar.vault.VaultDatabase
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
 *  - [persistDocumentChunks] writes all chunk rows in ONE transaction (unchanged).
 *  - [deletePartialByContentHash] is a SEPARATE, non-transactional delete — deliberately NOT
 *    folded into the insert transaction, exactly as Phase 0 designed it: a crash between the
 *    repair-delete and the re-insert self-heals on the next idempotent retry.
 */
class IndexPersister(private val db: VaultDatabase) {

    private val vaultDao get() = db.vaultDao()
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

    /** Document path: all chunk rows, atomically. */
    suspend fun persistDocumentChunks(items: List<VaultItem>) {
        db.withTransaction { vaultDao.insertAll(items) }
    }

    /** Document partial-repair: drop the incomplete rows before re-indexing (non-atomic, by design). */
    suspend fun deletePartialByContentHash(contentHash: String) {
        vaultDao.deleteByContentHash(contentHash)
    }
}
