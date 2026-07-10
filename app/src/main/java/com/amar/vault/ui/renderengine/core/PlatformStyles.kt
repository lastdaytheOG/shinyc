package com.amar.vault.ui.renderengine.core

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.amar.vault.ui.renderengine.models.PlatformStyle
import com.amar.vault.ui.renderengine.models.ContentType

/**
 * Central registry of per-platform visual identity.
 *
 * Each [PlatformStyle] carries:
 *  - background : soft tinted card surface (never harsh)
 *  - foreground : primary text color on that surface
 *  - accent     : the vivid brand color (used for badges, dots, placeholders)
 *  - gradient   : an accent-derived soft gradient for thumbnail placeholders / heroes
 *
 * This is the single source of truth referenced by PlatformBadge / PlatformAccent /
 * PlatformMetadata so platform identity is never hardcoded inside renderers.
 */
object PlatformStyles {

    private fun style(background: Long, accent: Long, foreground: Long = 0xFF1C1C1E): PlatformStyle {
        val accentColor = Color(accent)
        return PlatformStyle(
            background = Color(background),
            foreground = Color(foreground),
            accent = accentColor,
            gradient = Brush.linearGradient(
                listOf(accentColor.copy(alpha = 0.22f), accentColor.copy(alpha = 0.06f))
            )
        )
    }

    val Generic     = style(0xFFF6F5F3, 0xFF8A7F72)
    val YouTube     = style(0xFFFDECEB, 0xFFFF0000)
    val Instagram   = style(0xFFFDF0F6, 0xFFE1306C)
    val Amazon      = style(0xFFF3F7FB, 0xFFFF9900)
    val Article     = style(0xFFF4F4F5, 0xFF3A3A3C)
    val Spotify     = style(0xFFEFF8F1, 0xFF1DB954)
    val AppleMusic  = style(0xFFFDEFF1, 0xFFFA2D48)
    val Reddit      = style(0xFFFFF1EC, 0xFFFF4500)
    val PlayStore   = style(0xFFEFF4FD, 0xFF00C4B4)
    val Pinterest   = style(0xFFFDEDEE, 0xFFE60023)
    val GitHub      = style(0xFFF1F2F4, 0xFF24292F)
    val TikTok      = style(0xFFF1F1F3, 0xFF010101)
    val Twitter     = style(0xFFEEF6FE, 0xFF1D9BF0)
    val LinkedIn    = style(0xFFEDF4FA, 0xFF0A66C2)
    val Chrome      = style(0xFFEFF5FD, 0xFF4285F4)
    val Pdf         = style(0xFFFDECEA, 0xFFE53935)
    val Photo       = style(0xFFEFF4F4, 0xFF00C7BE)
    val Video       = style(0xFFF1EEF9, 0xFF5856D6)

    fun getStyleFor(contentType: ContentType): PlatformStyle = when (contentType) {
        ContentType.YOUTUBE_VIDEO -> YouTube
        ContentType.INSTAGRAM_REEL, ContentType.INSTAGRAM_POST -> Instagram
        ContentType.PRODUCT -> Amazon
        ContentType.ARTICLE, ContentType.RECIPE -> Article
        ContentType.SPOTIFY_SONG -> Spotify
        ContentType.APPLE_MUSIC -> AppleMusic
        ContentType.REDDIT_POST -> Reddit
        ContentType.APPLICATION -> PlayStore
        ContentType.PINTEREST_PIN -> Pinterest
        ContentType.GITHUB_REPO -> GitHub
        ContentType.TIKTOK -> TikTok
        ContentType.TWITTER_POST -> Twitter
        ContentType.LINKEDIN_POST -> LinkedIn
        ContentType.PDF, ContentType.DOCUMENT -> Pdf
        ContentType.PHOTO, ContentType.SCREENSHOT -> Photo
        ContentType.VIDEO -> Video
        ContentType.WEBSITE -> Chrome
        else -> Generic
    }

    /** Human-readable platform label used on badges and the metadata row. */
    fun labelFor(contentType: ContentType): String? = when (contentType) {
        ContentType.YOUTUBE_VIDEO -> "YouTube"
        ContentType.INSTAGRAM_REEL -> "Reel"
        ContentType.INSTAGRAM_POST -> "Instagram"
        ContentType.PRODUCT -> "Shop"
        ContentType.ARTICLE -> "Article"
        ContentType.RECIPE -> "Recipe"
        ContentType.SPOTIFY_SONG -> "Spotify"
        ContentType.APPLE_MUSIC -> "Music"
        ContentType.REDDIT_POST -> "Reddit"
        ContentType.APPLICATION -> "App"
        ContentType.PINTEREST_PIN -> "Pinterest"
        ContentType.GITHUB_REPO -> "GitHub"
        ContentType.TIKTOK -> "TikTok"
        ContentType.TWITTER_POST -> "Post"
        ContentType.LINKEDIN_POST -> "LinkedIn"
        ContentType.PDF -> "PDF"
        ContentType.DOCUMENT -> "Doc"
        ContentType.SCREENSHOT -> "Screenshot"
        ContentType.PHOTO -> "Photo"
        ContentType.VIDEO -> "Video"
        ContentType.WEBSITE -> "Link"
        else -> null
    }

    /** Emoji glyph used as a last-resort thumbnail fallback + badge icon. */
    fun iconFor(contentType: ContentType): String = when (contentType) {
        ContentType.YOUTUBE_VIDEO -> "▶"
        ContentType.INSTAGRAM_REEL -> "🎬"
        ContentType.INSTAGRAM_POST -> "📷"
        ContentType.PRODUCT -> "🛍"
        ContentType.ARTICLE -> "📰"
        ContentType.RECIPE -> "🍳"
        ContentType.SPOTIFY_SONG, ContentType.APPLE_MUSIC -> "🎵"
        ContentType.REDDIT_POST -> "👽"
        ContentType.APPLICATION -> "📦"
        ContentType.PINTEREST_PIN -> "📌"
        ContentType.GITHUB_REPO -> "🐙"
        ContentType.TIKTOK -> "🎵"
        ContentType.TWITTER_POST -> "𝕏"
        ContentType.LINKEDIN_POST -> "💼"
        ContentType.PDF -> "📄"
        ContentType.DOCUMENT -> "📝"
        ContentType.SCREENSHOT -> "📱"
        ContentType.PHOTO -> "🖼"
        ContentType.VIDEO -> "🎞"
        ContentType.WEBSITE -> "🔗"
        else -> "✦"
    }
}
