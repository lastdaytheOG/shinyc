package com.amar.vault.ui.renderengine.models

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.amar.vault.pipeline.models.UniversalMetadata

data class PlatformStyle(
    val background: Color,
    val foreground: Color,
    val accent: Color,
    val gradient: Brush? = null
)

data class BadgeInfo(
    val id: String,
    val text: String,
    val icon: String? = null,
    val style: PlatformStyle,
    val priority: Int = 0 // Higher priority badges appear first
)

enum class ContentType {
    INSTAGRAM_REEL,
    INSTAGRAM_POST,
    YOUTUBE_VIDEO,
    TIKTOK,
    TWITTER_POST,
    PINTEREST_PIN,
    SPOTIFY_SONG,
    APPLE_MUSIC,
    ARTICLE,
    RECIPE,
    PRODUCT,
    APPLICATION,
    SCREENSHOT,
    PHOTO,
    VIDEO,
    PDF,
    DOCUMENT,
    GITHUB_REPO,
    REDDIT_POST,
    LINKEDIN_POST,
    WEBSITE,
    GENERIC_FALLBACK
}

data class SavedItemAction(
    val id: String,
    val label: String,
    val icon: String,
    val onExecute: () -> Unit
)

/**
 * Runtime-derived facts about a saved item that are NOT persisted in the DB schema.
 * Computed off the main thread from the underlying file (image bounds, PDF page count,
 * file size) so cards can surface resolution / pages / size without a schema change.
 */
data class SavedDerivedFacts(
    val resolution: String? = null,     // e.g. "1080 × 1920"
    val pageCount: Int? = null,
    val fileSizeBytes: Long? = null,
    // Non-location EXIF for images (Phase 4). GPS is deliberately excluded.
    val exifCamera: String? = null,     // "Google Pixel 8 Pro"
    val exifLens: String? = null,
    val exifDateTaken: String? = null,  // human-formatted
    val exifSettings: String? = null    // "ƒ/1.8 · 1/120s · ISO 100 · 24mm"
) {
    val hasExif: Boolean
        get() = exifCamera != null || exifLens != null || exifDateTaken != null || exifSettings != null

    companion object {
        val EMPTY = SavedDerivedFacts()
    }
}

data class RichSavedItem(
    val id: String,
    val vaultItemId: String,
    val contentType: ContentType,
    val title: String,
    val subtitle: String?,
    val thumbnail: String?,
    val preview: String?,
    val badges: List<BadgeInfo>,
    val actions: List<SavedItemAction>,
    val isFavorite: Boolean,
    val style: PlatformStyle,
    val rawMetadata: UniversalMetadata,
    val rawStashItem: com.amar.vault.StashItemWithVaultItem,
    // ── Phase 2 premium-card fields (UI only, defaulted for backward-compat) ──
    val platformLabel: String? = null,   // "YouTube", "Instagram", "Amazon"…
    val sourceLabel: String? = null,     // channel / creator / domain / app name
    val savedAtMillis: Long = 0L,        // for relative "saved 2h ago" line
    val infoChips: List<String> = emptyList(), // derived facts: "12 pages", "2.4 MB"…
    val derived: SavedDerivedFacts = SavedDerivedFacts.EMPTY // raw facts for the detail page
)
