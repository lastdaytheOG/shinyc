package com.amar.vault

import com.amar.vault.ui.renderengine.models.ContentType
import com.amar.vault.ui.renderengine.models.RichSavedItem

/** Album-style summary for a category tile: cover, count, last-updated. */
data class CategorySummary(
    val category: String,
    val count: Int,
    val lastUpdated: Long,
    val coverThumbnail: String?,
    /** Up to 4 real item thumbnails for the smart-preview collage (#5). Empty for empty folders. */
    val previewThumbnails: List<String> = emptyList()
)

/**
 * Visual personality of a folder (#4). Every option maps to a background treatment
 * in [com.amar.vault.ui.theme.FolderVisuals.styleFor]; PHOTO_COVER/COLOR_COVER also
 * drive whether the smart-preview collage or a flat accent fill shows behind the label.
 */
enum class FolderTheme(val label: String, val icon: String) {
    MINIMAL("Minimal", "◽"),
    DARK("Dark", "🌑"),
    GRADIENT("Gradient", "🌈"),
    GLASS("Glass", "🧊"),
    PHOTO_COVER("Photo", "🖼"),
    COLOR_COVER("Color", "🎨")
}

/**
 * Per-folder appearance chosen by the user in the create/edit sheet. Persisted in
 * DataStore ([SavedPreferences]) keyed by folder name — no DB/repository change.
 * [accent] is a packed ARGB Long so it survives JSON round-trips cleanly.
 */
data class FolderMeta(
    val theme: FolderTheme = FolderTheme.COLOR_COVER,
    val accent: Long = 0xFFAF52DE,
    val icon: String = "📁",
    val description: String? = null
)

/** The four in-Saved recent-activity feeds. */
enum class RecentActivityKind(val label: String, val icon: String) {
    SAVED("Recently Saved", "🆕"),
    OPENED("Recently Opened", "👁"),
    MOVED("Recently Moved", "📁"),
    FAVORITED("Recently Favorited", "❤️")
}

/** One-shot undo affordance surfaced as a snackbar after a destructive bulk action. */
data class SavedUndo(
    val message: String,
    val kind: Kind,
    val token: Long = System.nanoTime()
) {
    enum class Kind { DELETE, ARCHIVE }
}

/** Ordering options for the Saved feed. */
enum class SavedSortOption(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    RECENTLY_OPENED("Recently opened"),
    ALPHABETICAL("A – Z"),
    SOURCE("Source"),
    CATEGORY("Category")
}

/** Content-type filters exposed on the Saved feed. */
enum class SavedTypeFilter(val label: String, val icon: String) {
    ALL("All", "✦"),
    INSTAGRAM("Instagram", "🎬"),
    YOUTUBE("YouTube", "▶"),
    SHOPPING("Shopping", "🛍"),
    ARTICLES("Articles", "📰"),
    IMAGES("Images", "🖼"),
    VIDEOS("Videos", "🎞"),
    PDF("PDF", "📄"),
    DOCUMENTS("Documents", "📝"),
    APPS("Apps", "📦");

    fun matches(type: ContentType): Boolean = when (this) {
        ALL -> true
        INSTAGRAM -> type == ContentType.INSTAGRAM_REEL || type == ContentType.INSTAGRAM_POST ||
            type == ContentType.TIKTOK || type == ContentType.PINTEREST_PIN
        YOUTUBE -> type == ContentType.YOUTUBE_VIDEO
        SHOPPING -> type == ContentType.PRODUCT
        ARTICLES -> type == ContentType.ARTICLE || type == ContentType.RECIPE
        IMAGES -> type == ContentType.PHOTO || type == ContentType.SCREENSHOT
        VIDEOS -> type == ContentType.VIDEO
        PDF -> type == ContentType.PDF
        DOCUMENTS -> type == ContentType.DOCUMENT
        APPS -> type == ContentType.APPLICATION
    }
}

/**
 * Mini in-Saved search (final UX). Pure, allocation-light filtering over the
 * already-loaded Saved list — no DB query, no global Search engine. Matches every
 * whitespace-separated token (AND semantics) against a per-item haystack built from
 * fields already resident in memory: title, source/domain, folder, platform,
 * filename, note and OCR/metadata text.
 */
object SavedSearch {

    fun filter(items: List<RichSavedItem>, query: String): List<RichSavedItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return items
        return items.filter { item ->
            val hay = searchableText(item)
            tokens.all { hay.contains(it) }
        }
    }

    private fun searchableText(item: RichSavedItem): String {
        val s = item.rawStashItem
        return buildString {
            append(item.title).append(' ')
            item.subtitle?.let { append(it).append(' ') }
            item.sourceLabel?.let { append(it).append(' ') }
            item.platformLabel?.let { append(it).append(' ') }
            append(s.category).append(' ')
            append(s.sourceFile).append(' ')
            s.userNote?.let { append(it).append(' ') }
            append(s.ocrText).append(' ')
            append(s.uri).append(' ')
            com.amar.vault.ui.renderengine.core.PlatformStyles.labelFor(item.contentType)?.let { append(it).append(' ') }
            if (item.infoChips.isNotEmpty()) append(item.infoChips.joinToString(" "))
        }.lowercase()
    }
}

/**
 * Pure, in-memory sorting + filtering over the already-loaded Saved list. No new
 * queries — the feed is fully materialised in [SearchViewModel.richStashItems].
 */
object SavedOrganizer {

    fun filter(items: List<RichSavedItem>, filter: SavedTypeFilter): List<RichSavedItem> =
        if (filter == SavedTypeFilter.ALL) items
        else items.filter { filter.matches(it.contentType) }

    /** Which filters actually have at least one matching item (so we hide empty chips). */
    fun availableFilters(items: List<RichSavedItem>): List<SavedTypeFilter> {
        val present = SavedTypeFilter.values().filter { f ->
            f == SavedTypeFilter.ALL || items.any { f.matches(it.contentType) }
        }
        return present
    }

    fun sort(
        items: List<RichSavedItem>,
        sort: SavedSortOption,
        recentlyOpened: List<String>
    ): List<RichSavedItem> = when (sort) {
        SavedSortOption.NEWEST -> items.sortedByDescending { it.savedAtMillis }
        SavedSortOption.OLDEST -> items.sortedBy { it.savedAtMillis }
        SavedSortOption.ALPHABETICAL -> items.sortedBy { it.title.lowercase() }
        SavedSortOption.SOURCE -> items.sortedWith(
            compareBy<RichSavedItem> { (it.sourceLabel ?: "￿").lowercase() }
                .thenByDescending { it.savedAtMillis }
        )
        SavedSortOption.CATEGORY -> items.sortedWith(
            compareBy<RichSavedItem> { it.rawStashItem.category.ifBlank { "￿" }.lowercase() }
                .thenByDescending { it.savedAtMillis }
        )
        SavedSortOption.RECENTLY_OPENED -> {
            val rank = recentlyOpened.withIndex().associate { (i, id) -> id to i }
            items.sortedWith(
                compareBy<RichSavedItem> { rank[it.id] ?: Int.MAX_VALUE }
                    .thenByDescending { it.savedAtMillis }
            )
        }
    }
}
