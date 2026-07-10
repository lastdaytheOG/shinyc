package com.amar.vault.ui.components.folder

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.amar.vault.ContentSpecies
import com.amar.vault.StashItemWithVaultItem
import com.amar.vault.ui.components.folder.cards.AppCard
import com.amar.vault.ui.components.folder.cards.ArticleCard
import com.amar.vault.ui.components.folder.cards.GenericCard
import com.amar.vault.ui.components.folder.cards.MusicCard
import com.amar.vault.ui.components.folder.cards.ProductCard
import com.amar.vault.ui.components.folder.cards.ReelCard
import com.amar.vault.ui.components.folder.cards.ScreenshotCard
import com.amar.vault.ui.components.folder.cards.WebsiteCard
import com.amar.vault.ui.components.folder.cards.YoutubeCard

@Composable
fun ContentCardDispatcher(
    item: StashItemWithVaultItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val species = ContentSpecies.classify(item)
    
    when (species) {
        ContentSpecies.REEL -> ReelCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        ContentSpecies.YOUTUBE_VIDEO -> YoutubeCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        ContentSpecies.APP_LISTING -> AppCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        ContentSpecies.PRODUCT -> ProductCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        ContentSpecies.DOCUMENT, ContentSpecies.PDF -> ArticleCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        ContentSpecies.SCREENSHOT -> ScreenshotCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        ContentSpecies.WEBSITE -> WebsiteCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        ContentSpecies.AUDIO -> MusicCard(item = item, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
        else -> GenericCard(item = item, species = species, onClick = onClick, onLongClick = onLongClick, modifier = modifier)
    }
}
