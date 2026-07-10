package com.amar.vault.ui.renderengine.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.amar.vault.ui.renderengine.models.ContentType
import com.amar.vault.ui.renderengine.models.RichSavedItem
import com.amar.vault.ui.renderengine.renderers.*

interface CardRenderer {
    /**
     * Pure rendering function. Must not contain side effects or queries.
     */
    @Composable
    fun Render(item: RichSavedItem, modifier: Modifier)
}

object CardRendererFactory {
    private val youtubeRenderer = YoutubeRenderer()
    private val instagramRenderer = InstagramRenderer()
    private val photoRenderer = PhotoRenderer()
    private val productRenderer = ProductRenderer()
    private val articleRenderer = ArticleRenderer()
    private val websiteRenderer = WebsiteRenderer()
    private val pdfRenderer = PdfRenderer()
    private val musicRenderer = MusicRenderer()
    private val genericRenderer = GenericRenderer()

    fun createRenderer(contentType: ContentType): CardRenderer {
        return when (contentType) {
            ContentType.YOUTUBE_VIDEO -> youtubeRenderer
            ContentType.INSTAGRAM_REEL, ContentType.INSTAGRAM_POST,
            ContentType.TIKTOK, ContentType.PINTEREST_PIN -> instagramRenderer
            ContentType.PHOTO, ContentType.SCREENSHOT, ContentType.VIDEO -> photoRenderer
            ContentType.PRODUCT -> productRenderer
            ContentType.ARTICLE, ContentType.RECIPE -> articleRenderer
            ContentType.PDF, ContentType.DOCUMENT -> pdfRenderer
            ContentType.SPOTIFY_SONG, ContentType.APPLE_MUSIC -> musicRenderer
            ContentType.WEBSITE, ContentType.TWITTER_POST, ContentType.REDDIT_POST,
            ContentType.GITHUB_REPO, ContentType.LINKEDIN_POST -> websiteRenderer
            else -> genericRenderer
        }
    }
}
