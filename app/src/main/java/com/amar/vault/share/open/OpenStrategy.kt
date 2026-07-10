package com.amar.vault.share.open

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * One self-contained way to open one class of content. Adding support for a new
 * platform or file type means adding one implementation and registering it in
 * [OpenStrategyResolver] — never editing a giant if/else chain.
 */
interface OpenStrategy {
    /** Stable identifier used in telemetry (e.g. "PlayStore", "Image"). */
    val name: String

    /** True if this strategy is applicable to [target]. */
    fun canHandle(target: OpenTarget): Boolean

    /**
     * Attempts to open [target]. Returns true if something was successfully
     * launched (including a graceful in-strategy fallback such as app→browser),
     * false to let the resolver try the next capable strategy.
     */
    fun open(context: Context, target: OpenTarget): Boolean
}

/**
 * Shared, crash-safe Intent launching used by every strategy. Centralizes the
 * NEW_TASK flag handling and swallows [android.content.ActivityNotFoundException]
 * so a missing handler degrades to the next fallback instead of crashing.
 */
internal object IntentLauncher {
    private const val TAG = "AmarOpen"

    fun start(context: Context, intent: Intent): Boolean {
        return try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "start failed for ${intent.data}: ${e.message}")
            false
        }
    }

    /** True if any installed activity can handle [intent] (respecting setPackage). */
    fun canResolve(context: Context, intent: Intent): Boolean {
        return context.packageManager.resolveActivity(intent, 0) != null
    }

    fun log(strategy: String, detail: String) {
        Log.i(TAG, "strategy=$strategy $detail")
    }
}
