package com.amar.vault.pipeline.stages

import com.amar.vault.pipeline.models.UniversalMetadata

object ThumbnailService {
    /**
     * Given the finalized metadata, resolves or downloads the best thumbnail.
     * Keeps extraction independent from networking.
     */
    suspend fun resolveThumbnail(metadata: UniversalMetadata): String? {
        // If it's a local file, we might return the local file path
        if (metadata.localFile != null) {
            return metadata.localFile
        }
        
        // If it's a YouTube URL, we can generate the deterministic thumbnail URL
        if (PlatformDetector.detectPlatform(metadata.canonicalUrl?.value ?: metadata.rawUri) == "YOUTUBE") {
            val videoId = extractYoutubeId(metadata.canonicalUrl?.value ?: metadata.rawUri)
            if (videoId != null) {
                return "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
            }
        }
        
        // TODO: In a real implementation, this service would fetch OG Images and cache them locally
        
        return null
    }
    
    private fun extractYoutubeId(url: String?): String? {
        if (url == null) return null
        val regex = Regex("v=([a-zA-Z0-9_-]+)")
        val match = regex.find(url)
        return match?.groupValues?.get(1)
    }
}

data class UIPreviewData(
    val previewTitle: String?,
    val previewSubtitle: String?,
    val badgeText: String?,
    val fallbackIcon: String,
    val displayColorHex: String
)

object PreviewGenerator {
    /**
     * Converts rich UniversalMetadata into safe, simple strings for the UI to bind to.
     */
    fun generate(metadata: UniversalMetadata): UIPreviewData {
        val title = metadata.title?.value ?: metadata.rawUri ?: "Saved Item"
        val subtitle = metadata.author?.value ?: metadata.domain?.value ?: ""
        
        val platform = PlatformDetector.detectPlatform(metadata.canonicalUrl?.value ?: metadata.rawUri)
        val badge = when (platform) {
            "YOUTUBE" -> "YouTube"
            "INSTAGRAM" -> "Instagram"
            "AMAZON" -> "Amazon"
            else -> metadata.domain?.value?.replaceFirstChar { it.uppercase() }
        }
        
        val icon = when (ContentDetector.detectType(metadata.rawUri, metadata.mimeType)) {
            "IMAGE" -> "🖼️"
            "VIDEO" -> "🎥"
            "PDF" -> "📄"
            else -> "🔗"
        }
        
        return UIPreviewData(
            previewTitle = title,
            previewSubtitle = subtitle,
            badgeText = badge,
            fallbackIcon = icon,
            displayColorHex = "#F0F0F0"
        )
    }
}
