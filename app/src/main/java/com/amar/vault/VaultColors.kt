package com.amar.vault.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import com.amar.vault.FolderMeta
import com.amar.vault.FolderTheme
// ═══════════════════════════════════════════════════════════════
// Warm Beige / Cream palette — inspired by the iOS reference
// ═══════════════════════════════════════════════════════════════

// Backgrounds
val Cream             = Color(0xFFF5F0EA)   // main background
val CreamLight        = Color(0xFFFAF7F3)   // card background
val CreamDark         = Color(0xFFEDE6DC)   // subtle dividers

// Text
val WarmBrown         = Color(0xFFB8A08A)   // "Home" title, muted labels
val WarmBrownDark     = Color(0xFF6B5B4F)   // body text
val CharcoalSoft      = Color(0xFF3D3530)   // primary text on cards

// Accent
val AgenticPink       = Color(0xFFE8A0C8)   // left side of agentic gradient
val AgenticBlue       = Color(0xFFA0C8F0)   // right side of agentic gradient

// Card
val CardBorder        = Color(0x1A000000)   // very subtle border
val ChevronGray       = Color(0xFFCBC3BA)   // > arrow color

// Icon backgrounds
val IconBgLight       = Color(0xFFF0EBE4)   // icon circle bg

// red-purple, red, green, gold, teal, orange, light blue, emerald, indigo, gray
// Content Species Dot and Accent Colors
val ReelColor         = Color(0xFFFF2D55) // Magenta/Rose
val YouTubeColor      = Color(0xFFFF3B30) // Crimson Red
val AudioColor        = Color(0xFF5856D6) // Royal Purple (general audio) or Spotify green
val SpotifyColor      = Color(0xFF1DB954) // Spotify Green
val ProductColor      = Color(0xFFFF9500) // Amber/Gold
val ScreenshotColor   = Color(0xFF00C7BE) // Teal
val PdfColor          = Color(0xFFFF453A) // PDF Orange-Red
val AppListingColor   = Color(0xFF007AFF) // Wallet/Store Blue
val LocationColor     = Color(0xFF34C759) // Map Pin Green
val WebsiteColor      = Color(0xFFAF52DE) // Purple Link
val DocumentColor     = Color(0xFF8E8E93) // Neutral gray

// Content Species Soft Background Wash Colors (10% opacity)
val ReelWash          = Color(0x1AFF2D55)
val YouTubeWash       = Color(0x1AFF3B30)
val AudioWash         = Color(0x1A5856D6)
val ProductWash       = Color(0x1AFF9500)
val ScreenshotWash    = Color(0x1A00C7BE)
val PdfWash           = Color(0x1AFF453A)
val AppListingWash    = Color(0x1A007AFF)
val LocationWash      = Color(0x1A34C759)
val WebsiteWash       = Color(0x1AAF52DE)
val DocumentWash      = Color(0x1A8E8E93)

// Strict Palette Defines for Folders
val StyleInstagram = FolderStyle(
    background = Brush.linearGradient(listOf(Color(0xFFFF1493), Color(0xFF8A2BE2))),
    foreground = Color.White,
    icon = "🎬", // Reel icon as requested earlier for Instagram/Reels
    accent = Color(0xFFFF1493)
)
val StyleRecipes = FolderStyle(
    background = SolidColor(Color(0xFFFF7F50)), // Coral
    foreground = Color.White,
    icon = "🍳",
    accent = Color(0xFFFF7F50)
)
val StyleProducts = FolderStyle(
    background = SolidColor(Color(0xFFFFA500)), // Orange
    foreground = Color.White,
    icon = "🛍️",
    accent = Color(0xFFFFA500)
)
val StyleScreenshots = FolderStyle(
    background = SolidColor(Color(0xFF00FFFF)), // Cyan
    foreground = Color.Black,
    icon = "📱",
    accent = Color(0xFF00FFFF)
)
val StyleArticles = FolderStyle(
    background = SolidColor(Color(0xFF4CAF50)), // Green
    foreground = Color.White,
    icon = "📰",
    accent = Color(0xFF4CAF50)
)
val StyleMusic = FolderStyle(
    background = SolidColor(Color(0xFF000000)), // Black
    foreground = Color.White,
    icon = "🎵",
    accent = Color(0xFF000000)
)
val StylePlaces = FolderStyle(
    background = SolidColor(Color(0xFF2ECC71)), // Emerald
    foreground = Color.White,
    icon = "📍",
    accent = Color(0xFF2ECC71)
)
val StyleInspiration = FolderStyle(
    background = SolidColor(Color(0xFFFFD700)), // Yellow
    foreground = Color.Black,
    icon = "💡",
    accent = Color(0xFFFFD700)
)
val StyleAllItems = FolderStyle(
    background = SolidColor(Color(0xFF3D3530)), // CharcoalSoft
    foreground = Color.White,
    icon = "🔮",
    accent = Color(0xFF3D3530)
)
val StyleDefault = FolderStyle(
    background = SolidColor(Color(0xFFAF52DE)), // Purple
    foreground = Color.White,
    icon = "📁",
    accent = Color(0xFFAF52DE)
)

/** Readable label color for a given fill. */
internal fun contrastOn(c: Color): Color = if (c.luminance() > 0.55f) CharcoalSoft else Color.White

/** A slightly darker shade of [this], for gradient stops. */
internal fun Color.darker(factor: Float = 0.72f): Color =
    Color(red * factor, green * factor, blue * factor, alpha)

object FolderVisuals {
    /**
     * Theme-aware style (#4). When the user has chosen a [FolderMeta], its theme +
     * accent + icon win; otherwise we fall back to the name-derived defaults in
     * [getStyle], so untouched folders look exactly as before.
     */
    fun styleFor(category: String, meta: FolderMeta?): FolderStyle {
        if (meta == null) return getStyle(category)
        val accent = Color(meta.accent)
        val icon = meta.icon.ifBlank { getStyle(category).icon }
        return when (meta.theme) {
            FolderTheme.MINIMAL -> FolderStyle(
                background = SolidColor(CreamLight),
                foreground = CharcoalSoft,
                icon = icon,
                accent = accent
            )
            FolderTheme.DARK -> FolderStyle(
                background = SolidColor(Color(0xFF1C1A18)),
                foreground = Color.White,
                icon = icon,
                accent = accent
            )
            FolderTheme.GRADIENT, FolderTheme.PHOTO_COVER -> FolderStyle(
                background = Brush.linearGradient(listOf(accent, accent.darker())),
                foreground = Color.White,
                icon = icon,
                accent = accent
            )
            FolderTheme.GLASS -> FolderStyle(
                background = Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.12f))
                ),
                foreground = CharcoalSoft,
                icon = icon,
                accent = accent
            )
            FolderTheme.COLOR_COVER -> FolderStyle(
                background = SolidColor(accent),
                foreground = contrastOn(accent),
                icon = icon,
                accent = accent
            )
        }
    }

    fun getStyle(category: String): FolderStyle {
        val key = category.trim().lowercase()
        return when {
            key == "" || key == "all" || key == "all items" -> StyleAllItems
            key == "instagram" || key == "reels" || key == "reel" -> StyleInstagram
            key == "recipes" || key == "recipe" -> StyleRecipes
            key == "products" || key == "product" || key == "shopping" -> StyleProducts
            key == "screenshots" || key == "screenshot" -> StyleScreenshots
            key == "articles" || key == "article" || key == "reading" -> StyleArticles
            key == "music" || key == "audio" -> StyleMusic
            key == "places" || key == "place" || key == "travel" -> StylePlaces
            key == "inspiration" || key == "ideas" || key == "inspo" -> StyleInspiration
            key == "youtube" -> FolderStyle(SolidColor(Color(0xFF4ECDC4)), Color.White, "📁", Color(0xFF4ECDC4))
            key == "motu patlu" -> FolderStyle(SolidColor(Color(0xFFFFC107)), Color.White, "📁", Color(0xFFFFC107))
            key == "ai" -> FolderStyle(SolidColor(Color(0xFFE066FF)), Color.White, "🤖", Color(0xFFE066FF))
            key == "games" || key == "gaming" -> FolderStyle(SolidColor(Color(0xFF2ECC71)), Color.White, "🎮", Color(0xFF2ECC71))
            else -> StyleDefault
        }
    }
}