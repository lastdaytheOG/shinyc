package com.amar.vault

import android.content.Context
import androidx.core.content.pm.ShortcutManagerCompat
import java.io.File

/**
 * Clears what the Saved screen left outside the database on a phone that had it: its folders,
 * pins and recent trail, the link thumbnails it downloaded, and its folders in Android's share
 * sheet. Its table in the database goes in the step to version 17.
 */
internal object SavedLeftovers {
    private const val PREFS = "saved_leftovers"
    private const val KEY_CLEARED = "cleared"

    fun clearOnce(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CLEARED, false)) return
        File(context.filesDir, "datastore/saved_prefs.preferences_pb").delete()
        File(context.filesDir, "thumbnails").deleteRecursively()
        runCatching { ShortcutManagerCompat.removeAllDynamicShortcuts(context) }
            .onFailure { VaultLog.e("SavedLeftovers", "Share sheet folders were not removed", it) }
        prefs.edit().putBoolean(KEY_CLEARED, true).apply()
    }
}
