package com.amar.vault.indexing

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.amar.vault.VaultDatabase
import kotlinx.coroutines.delay

/**
 * The single, shared discovery engine for every indexing trigger.
 *
 * Owns exactly — and only:
 *  1. MediaStore query construction (from [DiscoverySpec]),
 *  2. candidate discovery (cursor → content URIs),
 *  3. dedup against already-indexed content,
 *  4. the pacing loop (driven by a supplied [BatchPolicy]),
 *  5. orchestration into the indexing pipeline (invoking the supplied per-item action in paced,
 *     dedup-filtered, `DATE_ADDED DESC` order).
 *
 * It owns none of: scheduling, WorkManager, notifications, lifecycle, foreground-service logic,
 * trigger-specific behaviour, or Android execution policy. Those stay in the triggers, which pass
 * their concerns in as the spec, the policy, and a small set of lifecycle hooks. There is **no**
 * `if (Bulk) / if (Foreground) / if (Nightly)` anywhere here — the engine is trigger-agnostic.
 *
 * Concrete (no interface — single implementation, no polymorphism), per the standing rule.
 *
 * Discovery and processing are two methods so a trigger can inject its own between-the-two lifecycle
 * (empty-state handling, "found N" notifications, session bracketing) without the engine knowing.
 */
class DiscoveryEngine(context: Context) {

    private val contentResolver = context.contentResolver
    private val dao = VaultDatabase.get(context).vaultDao()

    /** Result of a processing run. [cancelled] is true iff the run stopped on a cooperative cancel. */
    data class DiscoveryOutcome(val cancelled: Boolean)

    // ── 1–3. MediaStore query + candidate discovery + dedup ───────────────────

    /**
     * Query MediaStore per [spec], build content URIs (`DATE_ADDED DESC`), and — when
     * [DiscoverySpec.dedupAgainstIndexed] — drop URIs already present in Room. Ordering is
     * preserved through the dedup filter.
     */
    suspend fun discover(spec: DiscoverySpec): List<Uri> {
        val collection = resolveCollection(spec.mediaScope)

        // Only _ID is ever read to build the URI; RELATIVE_PATH / DATE_ADDED are referenced by the
        // selection, which SQLite evaluates regardless of projection — so a canonical [_ID]
        // projection returns the identical URI list the per-trigger projections did.
        val projection = arrayOf(MediaStore.Images.Media._ID)

        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (spec.folders.isNotEmpty() && !spec.folders.contains("")) {
            clauses += "(" + spec.folders.joinToString(" OR ") {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            } + ")"
            args += spec.folders.map { "%$it%" }
        }
        if (spec.sinceEpochSeconds != null) {
            clauses += "${MediaStore.Images.Media.DATE_ADDED} > ?"
            args += spec.sinceEpochSeconds.toString()
        }
        val selection = if (clauses.isEmpty()) null else clauses.joinToString(" AND ")
        val selectionArgs = if (args.isEmpty()) null else args.toTypedArray()
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val uris = mutableListOf<Uri>()
        contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                uris += ContentUris.withAppendedId(collection, c.getLong(idCol))
            }
        }

        if (!spec.dedupAgainstIndexed) return uris
        val indexed = dao.getAllUris().toHashSet()
        return uris.filter { it.toString() !in indexed }
    }

    private fun resolveCollection(scope: DiscoverySpec.MediaScope): Uri = when (scope) {
        DiscoverySpec.MediaScope.ALL_VOLUMES ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        DiscoverySpec.MediaScope.PRIMARY_EXTERNAL ->
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    // ── 4–5. Pacing loop + orchestration into the pipeline ────────────────────

    /**
     * Run [candidates] through [indexOne] in [policy]-sized batches.
     *
     * @param isActive         cooperative cancellation source (the trigger's own signal). Checked
     *                         before every item; when it turns false the run stops and reports
     *                         [DiscoveryOutcome.cancelled] = true.
     * @param indexOne         per-candidate action — the trigger owns bitmap decode, the
     *                         `indexBitmap` call, itemType, recycle, and per-item error/metrics.
     *                         Receives the candidate's index and the total for progress reporting.
     * @param onBatchCommitted invoked after each batch (before its pacing delay), with the number of
     *                         items processed so far and the total. Triggers use it for per-batch
     *                         checkpointing (nightly `saveState`) or per-batch progress (foreground).
     */
    suspend fun process(
        candidates: List<Uri>,
        policy: BatchPolicy,
        isActive: () -> Boolean,
        indexOne: suspend (index: Int, total: Int, uri: Uri) -> Unit,
        onBatchCommitted: suspend (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): DiscoveryOutcome {
        val total = candidates.size
        var index = 0
        var processed = 0
        var cancelled = false

        while (index < total && isActive()) {
            val end = minOf(index + policy.nextBatchSize(total - index), total)
            var j = index
            while (j < end) {
                if (!isActive()) { cancelled = true; break }
                indexOne(j, total, candidates[j])
                processed++
                j++
            }
            index = end

            onBatchCommitted(processed, total)

            val isFinalBatch = index >= total
            val delayMs = policy.postBatchDelayMs(isFinalBatch)
            if (delayMs > 0 && isActive()) delay(delayMs)

            if (cancelled) break
        }

        return DiscoveryOutcome(cancelled = cancelled)
    }
}
