package com.amar.vault

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.amar.vault.share.open.OpenStrategyResolver
import com.amar.vault.share.open.OpenTarget
import com.amar.vault.share.open.ShareContentUri

/**
 * Public facade for opening and sharing captured content.
 *
 * The routing logic now lives in the modular strategy layer
 * ([OpenStrategyResolver] + the individual [com.amar.vault.share.open.OpenStrategy]
 * implementations). This object only builds an [OpenTarget] and delegates, so
 * adding a new platform never touches this file.
 *
 * Public API is unchanged for existing callers (SavedScreen, CollectibleDetailView,
 * VaultNavHost, DocumentPickerScreen, OpenDispatcher).
 */
object ContentOpenManager {

    private val resolver: OpenStrategyResolver by lazy { OpenStrategyResolver.default() }

    fun open(context: Context, item: StashItemWithVaultItem) = open(context, item.toVaultItem())

    fun open(context: Context, item: VaultItem) {
        resolver.open(context, OpenTarget.from(context, item))
    }

    fun openOriginal(context: Context, item: StashItemWithVaultItem) = open(context, item)

    /** Copies the item's canonical link (or its uri) to the clipboard. */
    fun copyLink(context: Context, item: StashItemWithVaultItem) {
        val target = OpenTarget.from(context, item.toVaultItem())
        val link = target.url ?: item.uri
        if (link.isBlank()) {
            Toast.makeText(context, "No link to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("link", link))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    /** True when the item carries a shareable/openable web link. */
    fun hasLink(context: Context, item: StashItemWithVaultItem): Boolean {
        return OpenTarget.from(context, item.toVaultItem()).url != null ||
            item.uri.startsWith("http", ignoreCase = true)
    }

    fun share(context: Context, item: StashItemWithVaultItem) = share(context, item.toVaultItem())

    fun share(context: Context, item: VaultItem) {
        val target = OpenTarget.from(context, item)
        val intent = if (target.url != null || item.itemType == ItemType.TEXT) {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, target.url ?: item.ocrText)
                putExtra(Intent.EXTRA_SUBJECT, target.title)
            }
        } else {
            val stream = target.localUri ?: ShareContentUri.resolve(context, item.uri)
            Intent(Intent.ACTION_SEND).apply {
                type = target.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, stream)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startChooser(context, Intent.createChooser(intent, "Share"))
    }

    private fun startChooser(context: Context, intent: Intent) {
        try {
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to share this item", Toast.LENGTH_SHORT).show()
        }
    }
}
