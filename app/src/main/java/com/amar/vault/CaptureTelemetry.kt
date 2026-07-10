package com.amar.vault

import android.util.Log

/**
 * Structured, single-line telemetry for the Share / Saved capture pipeline.
 *
 * Every capture emits an ordered trail of [Stage] events tagged with the session
 * id, so a full capture can be reconstructed from logcat:
 *
 *   RECEIVED → PERMISSION_GRANTED → COPIED → METADATA_EXTRACTED → HASH_GENERATED
 *   → DEDUP_COMPLETE → VAULT_SAVED → STASH_SAVED → WORKER_QUEUED → WORKER_COMPLETE
 *
 * Failures never happen silently: [failure] records the stage, mime, uri,
 * exception, human reason and the recovery path taken.
 *
 * Logging only — introduces no new database or state.
 */
object CaptureTelemetry {
    private const val TAG = "AmarShareTelemetry"

    enum class Stage {
        RECEIVED,
        PERMISSION_GRANTED,
        COPIED,
        METADATA_EXTRACTED,
        HASH_GENERATED,
        DEDUP_COMPLETE,
        VAULT_SAVED,
        STASH_SAVED,
        WORKER_QUEUED,
        WORKER_COMPLETE,
        ENRICH_COMPLETE,
        CAPTURE_FAILED,
        ENRICH_FAILED,
    }

    /** Emit an ordered progress event. [detail] is appended as free-form key=value text. */
    fun stage(sessionId: String, stage: Stage, detail: String = "") {
        Log.i(TAG, "session=$sessionId stage=$stage $detail".trimEnd())
    }

    /**
     * Record a failure with full diagnostic context. Never throws.
     *
     * @param recovery the concrete action taken as a result (e.g. "attachment marked FAILED",
     *  "session marked FAILED, user notified").
     */
    fun failure(
        sessionId: String,
        stage: Stage,
        mime: String? = null,
        uri: String? = null,
        throwable: Throwable? = null,
        reason: String,
        recovery: String,
    ) {
        Log.e(
            TAG,
            "session=$sessionId stage=$stage FAILURE mime=${mime ?: "-"} uri=${uri ?: "-"} " +
                "reason=\"$reason\" recovery=\"$recovery\"",
            throwable,
        )
    }
}
