package com.amar.vault.ui.detail.core

import com.amar.vault.ui.detail.models.*
import com.amar.vault.ui.renderengine.core.SavedFactDeriver
import com.amar.vault.ui.renderengine.models.ContentType
import com.amar.vault.ui.renderengine.models.RichSavedItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DetailPresentationBuilder {

    private val savedDateFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    private fun formatSavedDate(millis: Long): String = savedDateFormat.format(Date(millis))

    fun build(
        item: RichSavedItem,
        onAction: (String) -> Unit,
        related: List<RichSavedItem> = emptyList()
    ): DetailPresentationModel {
        val theme = ThemeEngine.extractTheme(item)
        val hero = buildHero(item)

        val toolbar = ToolbarPresentation(
            title = item.platformLabel ?: item.contentType.name.replace("_", " "),
            subtitle = item.rawMetadata.domain?.value,
            isFavorite = item.isFavorite
        )

        val titleBlock = TitleBlockPresentation(
            title = item.title,
            domain = item.rawMetadata.domain?.value,
            author = item.rawMetadata.author?.value
        )

        val primaryActions = listOf(ActionProvider.primaryAction(item))
        val quickActions = ActionProvider.quickActions(item)
        val dangerZone = DangerZonePresentation(ActionProvider.managementActions(item))
        val relatedItems = if (related.isNotEmpty()) RelatedItemsPresentation(related) else null
        val sections = buildSections(item)

        return DetailPresentationModel(
            id = item.id,
            theme = theme,
            hero = hero,
            toolbar = toolbar,
            titleBlock = titleBlock,
            metadataChips = item.badges,
            primaryActions = primaryActions,
            quickActions = quickActions,
            informationSections = sections,
            relatedItems = relatedItems,
            dangerZone = dangerZone
        )
    }

    private fun buildHero(item: RichSavedItem): HeroPresentation {
        val thumb = item.thumbnail ?: item.preview
        return when (item.contentType) {
            ContentType.YOUTUBE_VIDEO, ContentType.INSTAGRAM_REEL, ContentType.TIKTOK, ContentType.VIDEO ->
                HeroPresentation.VideoHero(thumbnailUrl = thumb, videoUrl = item.rawMetadata.canonicalUrl?.value)
            ContentType.SPOTIFY_SONG, ContentType.APPLE_MUSIC ->
                HeroPresentation.AudioHero(thumbnailUrl = thumb, artist = item.rawMetadata.author?.value, title = item.title)
            ContentType.PDF, ContentType.DOCUMENT ->
                HeroPresentation.PdfHero(thumbnailUrl = thumb, title = item.title)
            ContentType.WEBSITE, ContentType.ARTICLE, ContentType.RECIPE ->
                HeroPresentation.WebsiteHero(thumbnailUrl = thumb, url = item.rawMetadata.canonicalUrl?.value)
            ContentType.PHOTO, ContentType.SCREENSHOT, ContentType.PRODUCT, ContentType.INSTAGRAM_POST ->
                HeroPresentation.ImageHero(url = thumb)
            else -> if (thumb != null) HeroPresentation.ImageHero(url = thumb) else HeroPresentation.FallbackHero
        }
    }

    private fun buildSections(item: RichSavedItem): List<DetailSection> {
        val sections = mutableListOf<DetailSection>()
        val meta = item.rawMetadata
        val d = item.derived

        // ── Content-adaptive details section ──────────────────────────────────
        val details = buildString {
            when (item.contentType) {
                ContentType.PHOTO, ContentType.SCREENSHOT -> {
                    d.resolution?.let { appendLine("Resolution: $it") }
                    d.exifCamera?.let { appendLine("Camera: $it") }
                    d.exifLens?.let { appendLine("Lens: $it") }
                    d.exifSettings?.let { appendLine("Settings: $it") }
                    d.exifDateTaken?.let { appendLine("Taken: $it") }
                    d.fileSizeBytes?.let { appendLine("Size: ${SavedFactDeriver.formatSize(it)}") }
                }
                ContentType.PDF, ContentType.DOCUMENT -> {
                    d.pageCount?.let { appendLine("Pages: $it") }
                    d.fileSizeBytes?.let { appendLine("Size: ${SavedFactDeriver.formatSize(it)}") }
                }
                ContentType.YOUTUBE_VIDEO, ContentType.VIDEO, ContentType.INSTAGRAM_REEL, ContentType.TIKTOK -> {
                    meta.author?.value?.let { appendLine("Channel: $it") }
                    meta.duration?.value?.takeIf { it > 0 }?.let { appendLine("Duration: ${formatDuration(it)}") }
                }
                ContentType.PRODUCT -> {
                    meta.domain?.value?.let { appendLine("Store: $it") }
                    meta.price?.value?.takeIf { it > 0 }?.let { appendLine("Price: ₹${trimPrice(it)}") }
                }
                ContentType.SPOTIFY_SONG, ContentType.APPLE_MUSIC -> {
                    meta.author?.value?.let { appendLine("Artist: $it") }
                    meta.duration?.value?.takeIf { it > 0 }?.let { appendLine("Duration: ${formatDuration(it)}") }
                }
                else -> {
                    meta.domain?.value?.let { appendLine("Site: $it") }
                    meta.author?.value?.let { appendLine("By: $it") }
                }
            }
        }.trim()
        if (details.isNotEmpty()) {
            sections.add(DetailSection("details", sectionTitleFor(item.contentType), details))
        }

        // ── Saved section ─────────────────────────────────────────────────────
        val savedInfo = buildString {
            appendLine("Category: ${item.rawStashItem.category.ifBlank { "Uncategorized" }}")
            if (item.savedAtMillis > 0L) appendLine("Saved: ${formatSavedDate(item.savedAtMillis)}")
            val source = item.sourceLabel ?: item.rawStashItem.sourceApp
            if (!source.isNullOrBlank()) appendLine("Source: $source")
            if (item.rawStashItem.vaultType == "ARCHIVED") appendLine("Status: Archived")
        }.trim()
        if (savedInfo.isNotEmpty()) sections.add(DetailSection("saved_info", "Saved", savedInfo))

        // ── Overview (description) ────────────────────────────────────────────
        if (!meta.description?.value.isNullOrBlank()) {
            sections.add(DetailSection("desc", "Overview", meta.description!!.value, isExpandable = true))
        }

        // ── Link ──────────────────────────────────────────────────────────────
        meta.canonicalUrl?.value?.let { url ->
            sections.add(DetailSection("link", "Link", url))
        }

        // ── Notes ─────────────────────────────────────────────────────────────
        val notes = item.rawStashItem.userNote ?: ""
        if (notes.isNotBlank()) sections.add(DetailSection("notes", "Notes", notes))

        return sections
    }

    private fun sectionTitleFor(type: ContentType): String = when (type) {
        ContentType.PHOTO, ContentType.SCREENSHOT -> "Photo details"
        ContentType.PDF, ContentType.DOCUMENT -> "Document"
        ContentType.YOUTUBE_VIDEO, ContentType.VIDEO, ContentType.INSTAGRAM_REEL, ContentType.TIKTOK -> "Video"
        ContentType.PRODUCT -> "Product"
        ContentType.SPOTIFY_SONG, ContentType.APPLE_MUSIC -> "Track"
        else -> "Details"
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).toInt()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
    }

    private fun trimPrice(p: Double): String = if (p % 1.0 == 0.0) p.toInt().toString() else "%.2f".format(p)
}
