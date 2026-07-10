package com.amar.vault.ui.renderengine.core

import com.amar.vault.StashItemWithVaultItem
import com.amar.vault.pipeline.models.UniversalMetadata
import com.amar.vault.ui.renderengine.models.ContentType

object ContentTypeResolver {
    fun resolve(stashItem: StashItemWithVaultItem, metadata: UniversalMetadata): ContentType {
        val domain = metadata.domain?.value?.lowercase() ?: ""
        val url = metadata.canonicalUrl?.value?.lowercase() ?: stashItem.uri.lowercase()
        val mime = stashItem.mimeType?.lowercase() ?: ""

        if (domain.contains("youtube.com") || domain.contains("youtu.be")) return ContentType.YOUTUBE_VIDEO
        
        if (domain.contains("instagram.com")) {
            if (url.contains("/reel/")) return ContentType.INSTAGRAM_REEL
            return ContentType.INSTAGRAM_POST
        }
        
        if (domain.contains("amazon.") || domain.contains("flipkart.com") ||
            domain.contains("myntra.com") || domain.contains("ebay.")) return ContentType.PRODUCT
        if (domain.contains("tiktok.com")) return ContentType.TIKTOK
        if (domain.contains("twitter.com") || domain.contains("x.com")) return ContentType.TWITTER_POST
        if (domain.contains("spotify.com")) return ContentType.SPOTIFY_SONG
        if (domain.contains("music.apple.com")) return ContentType.APPLE_MUSIC
        if (domain.contains("reddit.com")) return ContentType.REDDIT_POST
        if (domain.contains("pinterest.")) return ContentType.PINTEREST_PIN
        if (domain.contains("github.com")) return ContentType.GITHUB_REPO
        if (domain.contains("linkedin.com")) return ContentType.LINKEDIN_POST
        if (domain.contains("play.google.com") || domain.contains("apps.apple.com")) return ContentType.APPLICATION

        if (mime == "application/pdf") return ContentType.PDF
        if (mime.startsWith("image/")) {
            // Screenshots come in as images from the device; treat share-sheet screenshots
            // distinctly so they get the edge-to-edge photo treatment.
            return if (stashItem.sourceApp.contains("screenshot", ignoreCase = true) ||
                stashItem.sourceFile.contains("screenshot", ignoreCase = true)
            ) ContentType.SCREENSHOT else ContentType.PHOTO
        }
        if (mime.startsWith("video/")) return ContentType.VIDEO
        if (mime.startsWith("audio/")) return ContentType.APPLE_MUSIC

        // Fallback checks
        if (metadata.price?.value != null) return ContentType.PRODUCT
        if (metadata.author?.value != null && domain.isNotBlank()) return ContentType.ARTICLE
        
        if (domain.isNotBlank()) return ContentType.WEBSITE
        
        return ContentType.GENERIC_FALLBACK
    }
}
