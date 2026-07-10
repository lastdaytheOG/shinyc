package com.amar.vault.indexing

import android.graphics.Bitmap
import com.amar.vault.VaultDao
import java.security.MessageDigest

/**
 * Duplicate detection — deliberately **asymmetric** per modality, preserved verbatim from the two
 * orchestrators. The image and document paths solve different problems and are intentionally NOT
 * unified:
 *
 *  - **Image path → perceptual hash (pHash).** Near-identical screenshots collapse to the same
 *    64-bit hash; an exact-hash existence check skips re-indexing. The pHash is also the value
 *    stored on the item, so [imageVerdict] returns it alongside the verdict.
 *  - **Document path → exact content hash + partial-repair.** SHA-256 of the joined page text,
 *    plus a chunk-count comparison distinguishing a *complete* duplicate (skip) from a *partial*
 *    prior index (a crash mid-loop left half the chunks — repair by deleting the partial rows and
 *    re-indexing cleanly).
 *
 * Concrete (no interface — no polymorphism; the two paths share no substitutable contract), per the
 * standing rule. Performs only read queries + pure hashing. The partial-repair *delete* stays a
 * write owned by [IndexPersister], invoked by the orchestrator when the verdict is [DocVerdict.Partial]
 * — keeping IndexPersister the sole indexing-path Room writer (Phase 0 boundary, unchanged).
 */
class DuplicateDetector(private val dao: VaultDao) {

    // ── Image path: perceptual hash ───────────────────────────────────────────

    /** The computed pHash and whether an item with that exact pHash already exists. */
    data class ImageVerdict(val pHash: Long, val isDuplicate: Boolean)

    suspend fun imageVerdict(bitmap: Bitmap): ImageVerdict {
        val hash = computePHash(bitmap)
        return ImageVerdict(hash, dao.hashExists(hash))
    }

    // ── Document path: content hash + partial-repair ──────────────────────────

    /** Outcome of the document dedup check. [contentHash] is carried through in every case. */
    sealed interface DocVerdict {
        val contentHash: String
        /** No prior rows for this content — index fresh. */
        data class New(override val contentHash: String) : DocVerdict
        /** Fully indexed already — skip. */
        data class Duplicate(override val contentHash: String) : DocVerdict
        /** Partially indexed ([existingCount] of [expectedCount]) — repair then re-index. */
        data class Partial(
            override val contentHash: String,
            val existingCount: Int,
            val expectedCount: Int,
        ) : DocVerdict
    }

    suspend fun documentVerdict(pagedChunks: List<PagedChunk>): DocVerdict {
        val fullText = pagedChunks.joinToString("\n") { it.text }
        val contentHash = fullText.sha256()
        val expectedCount = pagedChunks.size
        val existingCount = dao.countByContentHash(contentHash)
        return when {
            // Fully indexed already → genuine duplicate (unchanged behaviour).
            existingCount == expectedCount -> DocVerdict.Duplicate(contentHash)
            // A prior run crashed mid-way and left a partial document → repair.
            existingCount > 0 -> DocVerdict.Partial(contentHash, existingCount, expectedCount)
            else -> DocVerdict.New(contentHash)
        }
    }

    // ── Hashing (verbatim from IndexingPipeline.computePHash / DocumentIndexer.sha256) ──

    private fun computePHash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        val grays = pixels.map { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        val avg  = grays.average()
        var hash = 0L
        grays.forEachIndexed { i, gray -> if (gray >= avg) hash = hash or (1L shl i) }
        scaled.recycle()
        return hash
    }

    private fun String.sha256(): String {
        val d = MessageDigest.getInstance("SHA-256"); val h = d.digest(toByteArray(Charsets.UTF_8))
        return h.joinToString("") { "%02x".format(it) }
    }
}
