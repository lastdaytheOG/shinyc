package com.amar.vault.pipeline.stages

import android.net.Uri

object ContentDetector {
    fun detectType(url: String?, mimeType: String?): String {
        if (mimeType?.startsWith("image/") == true) return "IMAGE"
        if (mimeType?.startsWith("video/") == true) return "VIDEO"
        if (mimeType == "application/pdf") return "PDF"
        if (!url.isNullOrBlank() && url.startsWith("http")) return "WEBSITE"
        return "UNKNOWN"
    }
}

object UrlNormalizer {
    fun normalize(url: String?): String? {
        if (url == null) return null
        var normalized = url.trim()
        
        // Expand youtu.be
        if (normalized.contains("youtu.be/")) {
            val videoId = normalized.substringAfter("youtu.be/").substringBefore("?")
            normalized = "https://www.youtube.com/watch?v=$videoId"
        }
        
        // Remove tracking params from amazon, instagram (naive implementation for architecture)
        if (normalized.contains("amazon.com") || normalized.contains("instagram.com")) {
            normalized = normalized.substringBefore("?")
        }
        
        // Convert mobile amazon to desktop
        if (normalized.startsWith("https://m.amazon.")) {
            normalized = normalized.replace("https://m.amazon.", "https://www.amazon.")
        }
        
        return normalized
    }
}

object PlatformDetector {
    fun detectPlatform(url: String?): String {
        if (url == null) return "UNKNOWN"
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") -> "YOUTUBE"
            lowerUrl.contains("instagram.com") -> "INSTAGRAM"
            lowerUrl.contains("amazon.com") || lowerUrl.contains("amzn.to") -> "AMAZON"
            lowerUrl.contains("tiktok.com") -> "TIKTOK"
            lowerUrl.contains("twitter.com") || lowerUrl.contains("x.com") -> "TWITTER"
            lowerUrl.contains("spotify.com") -> "SPOTIFY"
            lowerUrl.contains("apple.com/music") -> "APPLE_MUSIC"
            lowerUrl.contains("reddit.com") -> "REDDIT"
            lowerUrl.contains("pinterest.com") -> "PINTEREST"
            else -> "WEBSITE"
        }
    }
}
