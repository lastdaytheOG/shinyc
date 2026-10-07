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
        fun classify(item: StashItemWithVaultItem): ContentSpecies =
            classify(item.sourceApp, item.uri, item.itemType, item.mimeType, item.ocrText, item.sourceFile)

        fun classify(item: VaultItem): ContentSpecies =
            classify(item.sourceApp.orEmpty(), item.uri, item.itemType, item.mimeType, item.ocrText, item.sourceFile)

        /** True for a stored Word, Excel or EPUB file (a PDF has a species of its own). */
        fun isOfficeDocument(itemType: String, mimeType: String?, uri: String): Boolean {
            val type = itemType.uppercase()
            val mime = mimeType?.lowercase().orEmpty()
            return type == "WORD" || type == "EXCEL" || type == "EPUB" ||
                mime.contains("officedocument") || mime.contains("epub") ||
                OFFICE_EXTENSIONS.any { uri.trim().endsWith(it, ignoreCase = true) }
        }

        private val OFFICE_EXTENSIONS = listOf(".docx", ".xlsx", ".epub")

        /** One body for both item shapes, so the two cannot drift apart. */
        private fun classify(
            rawSourceApp: String, rawUri: String, rawItemType: String,
            rawMimeType: String?, ocrText: String, rawSourceFile: String,
        ): ContentSpecies {
            val sourceApp = rawSourceApp.trim()
            val uri = rawUri.trim()
            val itemType = rawItemType.uppercase()
            val mimeType = rawMimeType?.lowercase().orEmpty()
            val ocr = ocrText.lowercase()
            val sourceFile = rawSourceFile.lowercase()

            return when {
                // 1. YouTube video
                uri.contains("youtube.com", ignoreCase = true) || uri.contains("youtu.be", ignoreCase = true) || sourceApp.equals("YouTube", ignoreCase = true) || itemType == "YOUTUBE" -> YOUTUBE_VIDEO

                // 2. Reel / Instagram / Tiktok
                uri.contains("instagram.com", ignoreCase = true) || uri.contains("instagr.am", ignoreCase = true) || uri.contains("tiktok.com", ignoreCase = true) || sourceApp.equals("Instagram", ignoreCase = true) -> REEL

                // 3. Audio / Spotify
                uri.contains("spotify.com", ignoreCase = true) || uri.startsWith("spotify:", ignoreCase = true) || sourceApp.equals("Spotify", ignoreCase = true) || itemType == "AUDIO" || mimeType.startsWith("audio/") -> AUDIO

                // 4. Play Store App listings
                uri.contains("play.google.com", ignoreCase = true) || sourceApp.equals("Play Store", ignoreCase = true) -> APP_LISTING

                // 5. Stored documents. These come BEFORE the two rules below that read the text:
                //    for a document the text is a page of its content, so a PDF page that printed a
                //    rupee sign, a "$" or the word "distance" was drawn as a product or a map pin,
                //    titled "Saved Item", and its file could not be recognised in search results.
                itemType == "PDF" || mimeType.contains("pdf") || uri.endsWith(".pdf", ignoreCase = true) -> PDF
                isOfficeDocument(itemType, mimeType, uri) -> DOCUMENT

                // 6. Location / Map details
                uri.contains("maps.google", ignoreCase = true) || uri.contains("goo.gl/maps", ignoreCase = true) || uri.contains("maps.app.goo.gl", ignoreCase = true) || ocr.contains("location") || ocr.contains("maps.google") || ocr.contains("distance") -> LOCATION

                // 7. Product listings
                sourceApp.equals("Amazon", ignoreCase = true) || sourceApp.equals("Flipkart", ignoreCase = true) || uri.contains("amazon.", ignoreCase = true) || uri.contains("etsy.com", ignoreCase = true) || ocr.contains("₹") || ocr.contains("$") -> PRODUCT

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
