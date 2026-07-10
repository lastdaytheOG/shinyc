package com.amar.vault.ui.renderengine.core

import com.amar.vault.pipeline.models.UniversalMetadata
import com.amar.vault.ui.renderengine.models.BadgeInfo
import com.amar.vault.ui.renderengine.models.ContentType
import com.amar.vault.ui.renderengine.models.SavedDerivedFacts
import kotlin.math.roundToInt

object BadgeFactory {

    fun createPlatformBadge(contentType: ContentType): BadgeInfo? {
        val label = PlatformStyles.labelFor(contentType) ?: return null
        val style = PlatformStyles.getStyleFor(contentType)
        val icon = PlatformStyles.iconFor(contentType)
        return BadgeInfo("platform", label, icon, style, priority = 100)
    }

    fun createDurationBadge(durationMillis: Long?): BadgeInfo? {
        if (durationMillis == null || durationMillis <= 0L) return null
        val totalSeconds = (durationMillis / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val timeString = if (hours > 0)
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        else
            String.format("%d:%02d", minutes, seconds)
        return BadgeInfo("dur", timeString, "⏱", PlatformStyles.Generic, priority = 90)
    }

    fun createPriceBadge(price: Double?): BadgeInfo? {
        if (price == null || price <= 0.0) return null
        val formatted = if (price % 1.0 == 0.0) "₹${price.roundToInt()}" else "₹${"%.2f".format(price)}"
        return BadgeInfo("price", formatted, null, PlatformStyles.Amazon, priority = 95)
    }

    fun generateBadges(
        contentType: ContentType,
        metadata: UniversalMetadata,
        derived: SavedDerivedFacts = SavedDerivedFacts.EMPTY
    ): List<BadgeInfo> {
        val badges = mutableListOf<BadgeInfo>()
        createPlatformBadge(contentType)?.let { badges.add(it) }
        createDurationBadge(metadata.duration?.value)?.let { badges.add(it) }
        createPriceBadge(metadata.price?.value)?.let { badges.add(it) }
        return badges.sortedByDescending { it.priority }
    }
}

object ThumbnailStrategy {
    fun resolve(
        metadata: UniversalMetadata,
        storedThumbnail: String?,
        rawUri: String? = null,
        mimeType: String? = null
    ): String? {
        // 1. Generated/stored preview captured during ingestion (og:image, yt thumb…)
        if (!storedThumbnail.isNullOrBlank()) return storedThumbnail
        // 2. The local file itself for visual content (image / screenshot / photo).
        //    Coil can load content://, file:// and plain paths directly.
        val mime = mimeType?.lowercase().orEmpty()
        if (!rawUri.isNullOrBlank() && (mime.startsWith("image/") || mime.startsWith("video/"))) {
            return rawUri
        }
        if (!metadata.localFile.isNullOrBlank()) return metadata.localFile
        // 3. Fallback handled by SavedThumbnail's platform glyph
        return null
    }
}
