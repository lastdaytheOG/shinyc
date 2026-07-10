package com.amar.vault

import android.util.Log

/**
 * Centralized logging façade for the vault core.
 *
 * Why: the codebase had ~160 direct android.util.Log call sites with no level policy,
 * no way to silence logs in release, and raw user text (OCR content) being logged.
 * This is the single choke point that fixes all three without changing behaviour.
 *
 * Behaviour preservation: the default [minLevel] is VERBOSE, so every migrated call
 * still fires exactly as before. Release builds can raise the level (e.g. via
 * [setMinLevel]) to strip debug/verbose output — this is opt-in, not automatic.
 *
 * PII policy: never pass raw user content (OCR text, note bodies, query text) as a
 * message. Use [redact] / [len] to log a shape (length / hash) instead of the value.
 */
object VaultLog {

    enum class Level(val priority: Int) {
        VERBOSE(2), DEBUG(3), INFO(4), WARN(5), ERROR(6)
    }

    @Volatile
    private var minLevel: Level = Level.VERBOSE

    fun setMinLevel(level: Level) { minLevel = level }

    fun v(tag: String, msg: String) = log(Level.VERBOSE, tag, msg, null)
    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log(Level.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log(Level.ERROR, tag, msg, t)

    /** Redact potentially sensitive text to its length only. */
    fun len(text: String?): String = "len=${text?.length ?: 0}"

    /** Redact to a short, non-reversible fingerprint for correlation without exposing content. */
    fun redact(text: String?): String {
        if (text.isNullOrEmpty()) return "∅"
        return "len=${text.length},h=${Integer.toHexString(text.hashCode())}"
    }

    private fun log(level: Level, tag: String, msg: String, t: Throwable?) {
        if (level.priority < minLevel.priority) return
        when (level) {
            Level.VERBOSE -> Log.v(tag, msg)
            Level.DEBUG -> Log.d(tag, msg)
            Level.INFO -> Log.i(tag, msg)
            Level.WARN -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
            Level.ERROR -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
    }
}
