package com.amar.vault.ui.detail.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.amar.vault.ui.detail.models.*
import com.amar.vault.ui.renderengine.models.ContentType
import com.amar.vault.ui.renderengine.models.RichSavedItem

object ThemeEngine {
    fun extractTheme(item: RichSavedItem): DetailTheme {
        val style = item.style
        val base = Color(0xFFF9F9FB)
        // Subtle accent wash so each detail page feels tied to its platform.
        val background = lerp(base, style.accent, 0.05f)
        return DetailTheme(
            background = background,
            foreground = Color(0xFF1C1C1E),
            accent = style.accent,
            toolbarBackground = background.copy(alpha = 0.85f),
            isDark = false
        )
    }
}

/**
 * Reusable, contextual action provider for the Saved detail experience. Actions
 * adapt to content type and item state (favorite, archived, categorized, linked).
 * Consumers map the returned action `id`s to real operations.
 */
object ActionProvider {

    private fun hasLink(item: RichSavedItem): Boolean =
        item.rawMetadata.canonicalUrl?.value != null ||
            item.rawStashItem.uri.startsWith("http", ignoreCase = true)

    private fun isArchived(item: RichSavedItem): Boolean =
        item.rawStashItem.vaultType.equals("ARCHIVED", ignoreCase = true)

    private fun hasCategory(item: RichSavedItem): Boolean =
        item.rawStashItem.category.isNotBlank()

    /** The single, prominent, content-typed primary action. */
    fun primaryAction(item: RichSavedItem): ActionPresentation = when (item.contentType) {
        ContentType.YOUTUBE_VIDEO, ContentType.INSTAGRAM_REEL, ContentType.VIDEO, ContentType.TIKTOK ->
            ActionPresentation("play", "Play", "▶", isPrimary = true)
        ContentType.SPOTIFY_SONG, ContentType.APPLE_MUSIC ->
            ActionPresentation("listen", "Listen", "🎵", isPrimary = true)
        ContentType.ARTICLE, ContentType.RECIPE ->
            ActionPresentation("read", "Read", "📖", isPrimary = true)
        ContentType.PRODUCT ->
            ActionPresentation("buy", "View Item", "🛒", isPrimary = true)
        ContentType.WEBSITE ->
            ActionPresentation("visit", "Visit", "↗", isPrimary = true)
        ContentType.PDF, ContentType.DOCUMENT ->
            ActionPresentation("open_pdf", "Open", "📄", isPrimary = true)
        ContentType.PHOTO, ContentType.SCREENSHOT ->
            ActionPresentation("open", "View", "🖼", isPrimary = true)
        else ->
            ActionPresentation("open", "Open", "↗", isPrimary = true)
    }

    /** Secondary quick actions shown as an icon row. */
    fun quickActions(item: RichSavedItem): List<ActionPresentation> = buildList {
        add(ActionPresentation("favorite", if (item.isFavorite) "Favorited" else "Favorite", if (item.isFavorite) "❤️" else "🤍"))
        add(ActionPresentation("share", "Share", "🔗"))
        if (hasLink(item)) {
            add(ActionPresentation("copy_link", "Copy Link", "📋"))
            add(ActionPresentation("open_app", "Open App", "📲"))
        }
    }

    /** Organizational + destructive actions (rendered in the management sheet/zone). */
    fun managementActions(item: RichSavedItem): List<ActionPresentation> = buildList {
        add(ActionPresentation("move", "Move to Category", "📁"))
        if (hasCategory(item)) add(ActionPresentation("remove_category", "Remove from Category", "🏷"))
        if (isArchived(item)) add(ActionPresentation("unarchive", "Restore from Archive", "♻"))
        else add(ActionPresentation("archive", "Archive", "📦"))
        add(ActionPresentation("delete", "Delete", "🗑️", isDestructive = true))
    }

    // ── Backwards-compatible aliases (kept so existing callers still compile) ──
    fun generatePrimaryActions(item: RichSavedItem, onAction: (String) -> Unit): List<ActionPresentation> =
        listOf(primaryAction(item)) + quickActions(item).filter { it.id == "share" }

    fun generateDangerActions(item: RichSavedItem, onAction: (String) -> Unit): List<ActionPresentation> =
        managementActions(item)
}

object RelatedItemsProvider {
    /**
     * Related items are supplied by the caller (session-bundle attachments) and
     * injected into the presentation model, so this stays a pure placeholder.
     */
    fun getRelatedItems(item: RichSavedItem): RelatedItemsPresentation? = null
}
