package com.amar.vault.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.amar.vault.R
import com.amar.vault.ShareHandlerActivity

/**
 * Publishes the user's top folders as **Direct Share targets** using the official
 * `ShortcutManagerCompat` Sharing Shortcuts API (Phase 5C #2).
 *
 * When the user shares something from another app, Android may surface these as
 * "Amar Vault · <folder>" rows in the Direct Share strip at the top of the
 * Sharesheet, letting them save straight into a folder in one tap.
 *
 * ── Honest Android limitations ────────────────────────────────────────────────
 *  • This is the *maximum* an app can officially do. It cannot guarantee that
 *    Amar Vault appears at the top — the OS ranks Direct Share rows by its own
 *    usage/relevance signals, and OEM sheets (Samsung, MIUI, etc.) may rank or
 *    render them differently, or not show them at all.
 *  • Direct Share requires the app to have run at least once (dynamic shortcuts
 *    are runtime-published), so a brand-new install shows nothing until first use.
 *  • There is no API to force placement, pin ourselves above other apps, or bypass
 *    the OS chooser. We do not attempt any of that.
 *
 * The <share-target> in res/xml/shortcuts.xml + the manifest meta-data on the
 * launcher activity are the static half of this contract.
 */
object ShareTargetPublisher {

    const val CATEGORY = "com.amar.vault.category.SAVE_TO_FOLDER"
    const val EXTRA_PRESELECT_FOLDER = "preselect_folder"

    // Keep well under the per-app dynamic-shortcut cap; Direct Share only shows a few.
    private const val MAX_TARGETS = 4

    /** Republish the current top folders as Direct Share targets. Safe to call often. */
    fun publish(context: Context, folders: List<String>) {
        runCatching {
            val app = context.applicationContext
            val top = folders.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_TARGETS)
            if (top.isEmpty()) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(app)
                return
            }
            val icon = IconCompat.createWithResource(app, R.mipmap.ic_launcher)
            val shortcuts = top.map { name ->
                val intent = Intent(app, ShareHandlerActivity::class.java).apply {
                    action = Intent.ACTION_DEFAULT
                    putExtra(EXTRA_PRESELECT_FOLDER, name)
                }
                ShortcutInfoCompat.Builder(app, "share_folder_$name")
                    .setShortLabel(name)
                    .setLongLabel("Save to $name")
                    .setIcon(icon)
                    .setCategories(setOf(CATEGORY))
                    .setLongLived(true)
                    .setIntent(intent)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts)
        }
    }
}
