package com.amar.vault

import androidx.compose.ui.graphics.Color
import com.amar.vault.ui.theme.*

enum class ContentSpecies(
    val label: String,
    val emoji: String,
    val dotColor: Color,
    val washColor: Color,
    val aspectRatio: Float?, // null represents dynamic aspect ratio (like native screenshots)
    val isHorizontal: Boolean = false
) {
    REEL("Reel", "🎬", ReelColor, ReelWash, 9f / 16f),
    YOUTUBE_VIDEO("YouTube", "🎥", YouTubeColor, YouTubeWash, 16f / 9f),
    AUDIO("Audio", "🎵", SpotifyColor, AudioWash, 1f, isHorizontal = true),
    PRODUCT("Product", "🛍️", ProductColor, ProductWash, 1f),
    SCREENSHOT("Screenshot", "📱", ScreenshotColor, ScreenshotWash, null), // native tall ratio
    PDF("Document", "📄", PdfColor, PdfWash, 1.4f, isHorizontal = true),
    APP_LISTING("App", "👾", AppListingColor, AppListingWash, null, isHorizontal = true),
    LOCATION("Location", "📍", LocationColor, LocationWash, 1f),
    WEBSITE("Website", "🔗", WebsiteColor, WebsiteWash, 1.91f),
    DOCUMENT("Note", "📝", DocumentColor, DocumentWash, null);

    companion object {
        fun classify(item: StashItemWithVaultItem): ContentSpecies {
            val sourceApp = item.sourceApp.trim()
            val uri = item.uri.trim()
            val itemType = item.itemType.uppercase()
            val mimeType = item.mimeType?.lowercase().orEmpty()
            val ocr = item.ocrText.lowercase()
            val sourceFile = item.sourceFile.lowercase()

            return when {
                // 1. YouTube video
                uri.contains("youtube.com", ignoreCase = true) || uri.contains("youtu.be", ignoreCase = true) || sourceApp.equals("YouTube", ignoreCase = true) || itemType == "YOUTUBE" -> YOUTUBE_VIDEO
                
                // 2. Reel / Instagram / Tiktok
                uri.contains("instagram.com", ignoreCase = true) || uri.contains("instagr.am", ignoreCase = true) || uri.contains("tiktok.com", ignoreCase = true) || sourceApp.equals("Instagram", ignoreCase = true) -> REEL
                
                // 3. Audio / Spotify
                uri.contains("spotify.com", ignoreCase = true) || uri.startsWith("spotify:", ignoreCase = true) || sourceApp.equals("Spotify", ignoreCase = true) || itemType == "AUDIO" || mimeType.startsWith("audio/") -> AUDIO
                
                // 4. Play Store App listings
                uri.contains("play.google.com", ignoreCase = true) || sourceApp.equals("Play Store", ignoreCase = true) -> APP_LISTING

                // 5. Location / Map details
                uri.contains("maps.google", ignoreCase = true) || uri.contains("goo.gl/maps", ignoreCase = true) || uri.contains("maps.app.goo.gl", ignoreCase = true) || ocr.contains("location") || ocr.contains("maps.google") || ocr.contains("distance") -> LOCATION

                // 6. Product listings
                sourceApp.equals("Amazon", ignoreCase = true) || sourceApp.equals("Flipkart", ignoreCase = true) || uri.contains("amazon.", ignoreCase = true) || uri.contains("etsy.com", ignoreCase = true) || ocr.contains("₹") || ocr.contains("$") -> PRODUCT
                
                // 7. PDF Documents
                itemType == "PDF" || mimeType.contains("pdf") || uri.endsWith(".pdf", ignoreCase = true) -> PDF
                
                // 8. Screenshot
                itemType == "SCREENSHOT" || (itemType == "IMAGE" && sourceFile.contains("screenshot")) -> SCREENSHOT
                
                // Fallbacks
                isUrlLike(uri) -> WEBSITE
                else -> DOCUMENT
            }
        }

        fun classify(item: VaultItem): ContentSpecies {
            val sourceApp = item.sourceApp.orEmpty().trim()
            val uri = item.uri.trim()
            val itemType = item.itemType.uppercase()
            val mimeType = item.mimeType?.lowercase().orEmpty()
            val ocr = item.ocrText.lowercase()
            val sourceFile = item.sourceFile.lowercase()

            return when {
                // 1. YouTube video
                uri.contains("youtube.com", ignoreCase = true) || uri.contains("youtu.be", ignoreCase = true) || sourceApp.equals("YouTube", ignoreCase = true) || itemType == "YOUTUBE" -> YOUTUBE_VIDEO
                
                // 2. Reel / Instagram / Tiktok
                uri.contains("instagram.com", ignoreCase = true) || uri.contains("instagr.am", ignoreCase = true) || uri.contains("tiktok.com", ignoreCase = true) || sourceApp.equals("Instagram", ignoreCase = true) -> REEL
                
                // 3. Audio / Spotify
                uri.contains("spotify.com", ignoreCase = true) || uri.startsWith("spotify:", ignoreCase = true) || sourceApp.equals("Spotify", ignoreCase = true) || itemType == "AUDIO" || mimeType.startsWith("audio/") -> AUDIO
                
                // 4. Play Store App listings
                uri.contains("play.google.com", ignoreCase = true) || sourceApp.equals("Play Store", ignoreCase = true) -> APP_LISTING

                // 5. Location / Map details
                uri.contains("maps.google", ignoreCase = true) || uri.contains("goo.gl/maps", ignoreCase = true) || uri.contains("maps.app.goo.gl", ignoreCase = true) || ocr.contains("location") || ocr.contains("maps.google") || ocr.contains("distance") -> LOCATION

                // 6. Product listings
                sourceApp.equals("Amazon", ignoreCase = true) || sourceApp.equals("Flipkart", ignoreCase = true) || uri.contains("amazon.", ignoreCase = true) || uri.contains("etsy.com", ignoreCase = true) || ocr.contains("₹") || ocr.contains("$") -> PRODUCT
                
                // 7. PDF Documents
                itemType == "PDF" || mimeType.contains("pdf") || uri.endsWith(".pdf", ignoreCase = true) -> PDF
                
                // 8. Screenshot
                itemType == "SCREENSHOT" || (itemType == "IMAGE" && sourceFile.contains("screenshot")) -> SCREENSHOT
                
                // Fallbacks
                isUrlLike(uri) -> WEBSITE
                else -> DOCUMENT
            }
        }

        private fun isUrlLike(url: String): Boolean {
            val t = url.lowercase().trim()
            return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.") ||
                    t.endsWith(".com") || t.endsWith(".org") || t.endsWith(".net")
        }
    }
}
