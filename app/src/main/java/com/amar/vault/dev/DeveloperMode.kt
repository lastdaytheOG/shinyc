package com.amar.vault.dev

import android.content.Context
import com.amar.vault.BuildConfig
import com.amar.vault.ScanPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single gate for the internal Developer Tools suite.
 *
 * Developer Tools are an engineering-only toolkit — they must NEVER be reachable by an
 * ordinary production user. Access is granted when EITHER:
 *   - this is a debug build ([BuildConfig.DEBUG]), or
 *   - the user has explicitly flipped the Developer Mode toggle in Settings.
 *
 * Every Developer Tools entry point (Settings link, nav route, hub screen) checks this. The
 * tools themselves are pure wrappers over the existing architecture; the gate only controls
 * *visibility*, never production behaviour.
 */
object DeveloperMode {

    /** Reactive: true when Developer Tools should be reachable. */
    fun isEnabledFlow(context: Context): Flow<Boolean> =
        ScanPreferences.prefsFlow(context).map { BuildConfig.DEBUG || it.developerMode }

    /** True when Developer Tools should be reachable, given an already-loaded prefs snapshot. */
    fun isEnabled(prefs: ScanPreferences.Prefs): Boolean =
        BuildConfig.DEBUG || prefs.developerMode
}
