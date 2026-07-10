package com.amar.vault.ui.detail.models

import androidx.compose.ui.graphics.Color
import com.amar.vault.ui.renderengine.models.BadgeInfo

data class DetailTheme(
    val background: Color,
    val foreground: Color,
    val accent: Color,
    val toolbarBackground: Color,
    val isDark: Boolean
)

sealed class HeroPresentation {
    data class ImageHero(val url: String?) : HeroPresentation()
    data class VideoHero(val thumbnailUrl: String?, val videoUrl: String?) : HeroPresentation()
    data class WebsiteHero(val thumbnailUrl: String?, val url: String?) : HeroPresentation()
    data class AudioHero(val thumbnailUrl: String?, val artist: String?, val title: String?) : HeroPresentation()
    data class PdfHero(val thumbnailUrl: String?, val title: String?) : HeroPresentation()
    object FallbackHero : HeroPresentation()
}

data class ToolbarPresentation(
    val title: String,
    val subtitle: String?,
    val isFavorite: Boolean
)

data class TitleBlockPresentation(
    val title: String,
    val domain: String?,
    val author: String?
)

data class ActionPresentation(
    val id: String,
    val label: String,
    val icon: String,
    val isPrimary: Boolean = false,
    val isDestructive: Boolean = false
)

data class DetailSection(
    val id: String,
    val title: String,
    val content: String,
    val isExpandable: Boolean = false,
    val isEmpty: Boolean = false
)

data class RelatedItemsPresentation(
    val items: List<com.amar.vault.ui.renderengine.models.RichSavedItem>
)

data class DangerZonePresentation(
    val actions: List<ActionPresentation>
)

data class DetailPresentationModel(
    val id: String,
    val theme: DetailTheme,
    val hero: HeroPresentation,
    val toolbar: ToolbarPresentation,
    val titleBlock: TitleBlockPresentation,
    val metadataChips: List<BadgeInfo>,
    val primaryActions: List<ActionPresentation>,
    val quickActions: List<ActionPresentation>,
    val informationSections: List<DetailSection>,
    val relatedItems: RelatedItemsPresentation?,
    val dangerZone: DangerZonePresentation
)
