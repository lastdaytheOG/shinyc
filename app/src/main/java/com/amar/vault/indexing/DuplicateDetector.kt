package com.amar.vault.indexing

import android.graphics.Bitmap
import com.amar.vault.VaultDao
import com.amar.vault.VaultItem
import java.security.MessageDigest

/**
 * Duplicate detection — deliberately **asymmetric** per modality, preserved verbatim from the two
 * orchestrators. The image and document paths solve different problems and are intentionally NOT
 * unified:
 *
 *  - **Image path → perceptual hash (pHash), then the pixels.** A picture with the same 64-bit hash
 *    as a stored one is looked at beside it ([SamePicture]), and only the same picture is skipped.
 *    The hash is eight by eight cells of light and dark: on 36 system screens, All Apps and a dark
 *    Settings home had the hash of a screen that looks nothing like either, and were never stored
 *    and could never be found. The pHash is also the value stored on the item.
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
class DuplicateDetector(
    private val dao: VaultDao,
    /** A stored picture as it is on the phone; null when it cannot be read any more. */
    private val openStored: (uri: String) -> Bitmap? = { null },
) {

    // ── Image path: perceptual hash, then the pixels ──────────────────────────

    fun hashOf(bitmap: Bitmap): Long = computePHash(bitmap)

    /** Whether a stored item has this hash, whatever it looks like. */
    suspend fun hashStored(hash: Long): Boolean = dao.hashExists(hash)

    /**
     * The row this very picture is already stored under, if there is one: the one with its address,
     * or one with its hash that has its pixels. [bitmap] is the picture at [uri], decoded.
     */
    suspend fun storedCopy(bitmap: Bitmap, hash: Long, uri: String): VaultItem? {
        val rows = dao.picturesWithHash(hash)
        return rows.firstOrNull { it.uri == uri } ?: rows.firstOrNull { row ->
            val stored = openStored(row.uri) ?: return@firstOrNull false
            try {
                SamePicture.of(bitmap, stored)
            } finally {
                stored.recycle()
            }
        }
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
        val existingCount = dao.countChunksByContentHash(contentHash)
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

/**
 * Whether two decoded pictures are the same picture: the same size, and nowhere different by more
 * than a re-save at high quality makes them. Blocks are compared rather than the whole, so that one
 * changed figure on a receipt, which hardly moves an average over the picture, still makes it another
 * picture. Two screenshots that differ only in the clock are two pictures: nothing here can tell that
 * a clock does not matter and an amount does.
 */
internal object SamePicture {

    private const val BLOCK = 16

    /** How far a block's pixels may differ on average, of 255, in any colour. */
    private const val MOST_A_BLOCK_MAY_DIFFER = 4

    fun of(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val w = a.width
        val pa = IntArray(w * BLOCK)
        val pb = IntArray(w * BLOCK)
        var y = 0
        while (y < a.height) {
            val rows = minOf(BLOCK, a.height - y)
            a.getPixels(pa, 0, w, 0, y, w, rows)
            b.getPixels(pb, 0, w, 0, y, w, rows)
            if (!rowsAlike(pa, pb, w, rows)) return false
            y += rows
        }
        return true
    }

    /** [a] and [b] are [rows] rows of [w] pixels, up to [BLOCK] of them: is every block of them alike? */
    internal fun rowsAlike(a: IntArray, b: IntArray, w: Int, rows: Int): Boolean {
        val sums = IntArray(w / BLOCK + 1)
        val counts = IntArray(sums.size)
        for (r in 0 until rows) {
            val at = r * w
            for (x in 0 until w) {
                val p = a[at + x]
                val q = b[at + x]
                if (p == q) {
                    counts[x / BLOCK]++
                    continue
                }
                val dr = kotlin.math.abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF))
                val dg = kotlin.math.abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF))
                val db = kotlin.math.abs((p and 0xFF) - (q and 0xFF))
                sums[x / BLOCK] += maxOf(dr, dg, db)
                counts[x / BLOCK]++
            }
        }
        for (i in sums.indices) if (sums[i] > MOST_A_BLOCK_MAY_DIFFER * counts[i]) return false
        return true
    }
}
