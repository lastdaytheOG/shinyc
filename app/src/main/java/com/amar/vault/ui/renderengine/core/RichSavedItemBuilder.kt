package com.amar.vault.ui.renderengine.core

import com.amar.vault.StashItemWithVaultItem
import com.amar.vault.pipeline.models.UniversalMetadata
import com.amar.vault.ui.renderengine.models.ContentType
import com.amar.vault.ui.renderengine.models.RichSavedItem
import com.amar.vault.ui.renderengine.models.SavedDerivedFacts
import com.amar.vault.ui.renderengine.models.SavedItemAction

object RichSavedItemBuilder {
    fun build(
        stashItem: StashItemWithVaultItem,
        metadata: UniversalMetadata,
        derived: SavedDerivedFacts = SavedDerivedFacts.EMPTY,
        onActionExecuted: (String, String) -> Unit = { _, _ -> }
    ): RichSavedItem {

        val contentType = ContentTypeResolver.resolve(stashItem, metadata)

        val fallbackTitle = metadata.title?.value
            ?: stashItem.title
            ?: metadata.domain?.value
            ?: stashItem.sourceFile.substringAfterLast("/").ifBlank { "Saved item" }
        val title = fallbackTitle

        val subtitle = metadata.subtitle?.value ?: metadata.author?.value ?: metadata.description?.value

        // Source line = creator/channel/artist → domain → originating app.
        val sourceLabel = metadata.author?.value
            ?: metadata.domain?.value
            ?: prettyApp(stashItem.sourceApp)

        val thumbnail = ThumbnailStrategy.resolve(
            metadata = metadata,
            storedThumbnail = stashItem.thumbnailPath,
            rawUri = stashItem.uri,
            mimeType = stashItem.mimeType
        )
        val preview = metadata.description?.value

        val badges = BadgeFactory.generateBadges(contentType, metadata, derived)
        val style = PlatformStyles.getStyleFor(contentType)
        val platformLabel = PlatformStyles.labelFor(contentType)
        val infoChips = buildInfoChips(contentType, derived)

        val actions = buildActions(stashItem.stashId, onActionExecuted)

        return RichSavedItem(
            id = stashItem.stashId,
            vaultItemId = stashItem.vaultItemId,
            contentType = contentType,
            title = title,
            subtitle = subtitle,
            thumbnail = thumbnail,
            preview = preview,
            badges = badges,
            actions = actions,
            style = style,
            isFavorite = stashItem.isFavorite,
            rawMetadata = metadata,
            rawStashItem = stashItem,
            platformLabel = platformLabel,
            sourceLabel = sourceLabel,
            savedAtMillis = stashItem.savedAt,
            infoChips = infoChips,
            derived = derived
        )
    }

    private fun buildInfoChips(contentType: ContentType, derived: SavedDerivedFacts): List<String> {
        val chips = mutableListOf<String>()
        derived.pageCount?.let { chips.add(if (it == 1) "1 page" else "$it pages") }
        derived.resolution?.let { chips.add(it) }
        derived.fileSizeBytes?.let { chips.add(SavedFactDeriver.formatSize(it)) }
        return chips
    }

    private fun prettyApp(sourceApp: String): String? {
        if (sourceApp.isBlank()) return null
        // com.instagram.android → Instagram
        val leaf = sourceApp.substringAfterLast('.')
        return leaf.replaceFirstChar { it.uppercase() }
    }

    private fun buildActions(itemId: String, onExecute: (String, String) -> Unit): List<SavedItemAction> {
        return listOf(
            SavedItemAction("open", "Open", "↗", { onExecute("open", itemId) }),
            SavedItemAction("share", "Share", "🔗", { onExecute("share", itemId) }),
            SavedItemAction("favorite", "Favorite", "❤️", { onExecute("favorite", itemId) })
        )
    }
}
