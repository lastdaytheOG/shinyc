package com.amar.vault

import android.content.Context

/**
 * Small persisted health record that lets reconciliation run ONLY when justified,
 * instead of unconditionally on every launch.
 *
 * Rule of the integrity model: Room is the source of truth; the native BM25 and HNSW
 * indexes are rebuildable caches. They can drift only in a few well-defined situations,
 * and this record captures exactly those:
 *
 *   - interrupted indexing (process died mid-scan) → [wasIndexingInterrupted]
 *   - an explicit dirty flag set after a delete that couldn't fully propagate
 *   - a database version change (schema migration) → [dbVersionChanged]
 *
 * When none of these hold, the indexes are known-consistent and reconciliation is skipped.
 *
 * Backed by SharedPreferences (tiny, synchronous, no schema). Intentionally not a Room
 * table — it must be readable before/independently of the database and carries no
 * user data.
 */
class IndexHealthState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Interrupted-indexing detection (unclean shutdown) ─────────────────────

    /** Call when a bulk/foreground indexing session starts. */
    fun beginIndexingSession() {
        prefs.edit().putBoolean(KEY_INDEXING_IN_PROGRESS, true).apply()
    }

    /** Call when a bulk/foreground indexing session finishes cleanly. */
    fun endIndexingSession() {
        prefs.edit().putBoolean(KEY_INDEXING_IN_PROGRESS, false).apply()
    }

    /** True if a prior indexing session started but never ended cleanly. */
    fun wasIndexingInterrupted(): Boolean =
        prefs.getBoolean(KEY_INDEXING_IN_PROGRESS, false)

    // ── Explicit dirty flag (e.g. after a delete) ─────────────────────────────

    fun markVectorDirty() {
        prefs.edit().putBoolean(KEY_VECTOR_DIRTY, true).apply()
    }

    fun isVectorDirty(): Boolean = prefs.getBoolean(KEY_VECTOR_DIRTY, false)

    fun clearVectorDirty() {
        prefs.edit().putBoolean(KEY_VECTOR_DIRTY, false).apply()
    }

    // ── DB version change (migration) ─────────────────────────────────────────

    fun dbVersionChanged(currentVersion: Int): Boolean {
        val stored = prefs.getInt(KEY_DB_VERSION, -1)
        return stored != -1 && stored != currentVersion
    }

    fun recordDbVersion(currentVersion: Int) {
        prefs.edit().putInt(KEY_DB_VERSION, currentVersion).apply()
    }

    // ── Aggregate decision ────────────────────────────────────────────────────

    /** Reconciliation should run only if at least one justification holds. */
    fun shouldReconcile(currentDbVersion: Int): Boolean =
        wasIndexingInterrupted() || isVectorDirty() || dbVersionChanged(currentDbVersion)

    /** Clear all transient dirty signals after a successful reconciliation. */
    fun markReconciled(currentDbVersion: Int) {
        prefs.edit()
            .putBoolean(KEY_INDEXING_IN_PROGRESS, false)
            .putBoolean(KEY_VECTOR_DIRTY, false)
            .putInt(KEY_DB_VERSION, currentDbVersion)
            .apply()
    }

    companion object {
        private const val PREFS = "amar_index_health"
        private const val KEY_INDEXING_IN_PROGRESS = "indexing_in_progress"
        private const val KEY_VECTOR_DIRTY = "vector_dirty"
        private const val KEY_DB_VERSION = "db_version"

        /** Keep in sync with [VaultDatabase] @Database(version = ...). */
        const val CURRENT_DB_VERSION = 11
    }
}
