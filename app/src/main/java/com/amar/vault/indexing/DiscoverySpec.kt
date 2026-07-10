package com.amar.vault.indexing

/**
 * Immutable description of **what** a discovery run should scan — scope only.
 *
 * It carries no scheduling, no pacing, no trigger identity, no lifecycle, and no Android execution
 * policy. Each trigger builds the spec that reproduces its own MediaStore query semantics, and the
 * [DiscoveryEngine] operates purely from it (zero trigger branching).
 *
 * @property mediaScope        which MediaStore volume(s) to query (and build URIs against).
 * @property folders           `RELATIVE_PATH LIKE '%folder%'` scoping, OR-joined. Empty (or a blank
 *                             entry) means "no folder filter" — scan everything in scope.
 * @property sinceEpochSeconds incremental floor: only rows with `DATE_ADDED > sinceEpochSeconds`
 *                             (MediaStore stores DATE_ADDED in **seconds**). null = no floor.
 * @property dedupAgainstIndexed  when true, pre-filter candidates against the already-indexed URI
 *                             set (Room). When false, emit every candidate and rely on the indexing
 *                             pipeline's own pHash dedup (the bulk path's intentional behaviour).
 */
data class DiscoverySpec(
    val mediaScope: MediaScope,
    val folders: List<String> = emptyList(),
    val sinceEpochSeconds: Long? = null,
    val dedupAgainstIndexed: Boolean,
) {
    enum class MediaScope {
        /** All shared-storage volumes (incl. removable) on Q+, else the primary external volume. */
        ALL_VOLUMES,
        /** The primary external volume only (`EXTERNAL_CONTENT_URI`). */
        PRIMARY_EXTERNAL,
    }
}
