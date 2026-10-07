package com.amar.vault

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Unit coverage for the Share capture helpers: URL extraction from arbitrary
 * shared text, tracking-param-insensitive dedup, and the content priority
 * resolver's routing decisions. Robolectric is required because both use
 * [android.net.Uri].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ShareCaptureUnitTest {

    // ── ShareUrlExtractor ─────────────────────────────────────────────

    @Test
    fun extractsBareUrl() {
        assertEquals("https://youtu.be/abc123", ShareUrlExtractor.extractFirstUrl("https://youtu.be/abc123"))
    }

    @Test
    fun extractsUrlEmbeddedInCaption() {
        val text = "Check out this reel 😍 https://www.instagram.com/reel/CxYz/?igshid=abc please"
        assertEquals("https://www.instagram.com/reel/CxYz/?igshid=abc", ShareUrlExtractor.extractFirstUrl(text))
        assertEquals("instagram.com", ShareUrlExtractor.extractDomain(text)?.removePrefix("www."))
    }

    @Test
    fun trailingPunctuationIsStripped() {
        assertEquals("https://example.com/page", ShareUrlExtractor.extractFirstUrl("see (https://example.com/page)."))
    }

    @Test
    fun noUrlReturnsNull() {
        assertNull(ShareUrlExtractor.extractFirstUrl("just a plain note with no link"))
        assertFalse(ShareUrlExtractor.containsUrl("plain note"))
    }

    @Test
    fun trackingParamsDoNotBreakDedup() {
        val a = ShareUrlExtractor.normalizeForHash("https://www.amazon.com/dp/B01?utm_source=ig&ref=abc")
        val b = ShareUrlExtractor.normalizeForHash("https://amazon.com/dp/B01?ref_src=xyz")
        assertEquals(a, b)
    }

    @Test
    fun meaningfulParamsPreserveDistinctIdentity() {
        val a = ShareUrlExtractor.normalizeForHash("https://youtube.com/watch?v=aaaaaaaaaaa&utm_source=x")
        val b = ShareUrlExtractor.normalizeForHash("https://youtube.com/watch?v=bbbbbbbbbbb")
        assertNotEquals("Different video ids must not dedup", a, b)
    }

    // ── ContentPriorityResolver ───────────────────────────────────────

    private fun textAttachment(text: String): IngestionAttachment = IngestionAttachment(
        id = UUID.randomUUID().toString(),
        sessionId = "s",
        status = AttachmentStatus.READY,
        errorCode = AttachmentError.NONE,
        attachmentType = "TEXT",
        originalUri = text,
        localPath = null,
        mimeType = "text/plain",
        filename = null,
        contentHash = "h",
        width = null, height = null, duration = null, fileSize = null,
        pageCount = null, domain = null, artist = null, album = null,
        latitude = null, longitude = null, thumbnailPath = null,
        previewTitle = null, rawExtrasJson = null,
    )

    @Test
    fun embeddedYouTubeLinkResolvesToYouTubeStrategy() {
        val res = ContentPriorityResolver().resolve(listOf(textAttachment("watch this https://youtu.be/abcdefghijk")))
        assertEquals(OpenStrategy.OPEN_YOUTUBE, res.recommendedOpenStrategy)
        assertEquals(ItemType.LINK, res.previewType)
        assertEquals(LinkSite.YOUTUBE, LinkSite.of(res.primaryAttachment.originalUri.orEmpty()))
    }

    @Test
    fun embeddedPlayStoreLinkResolvesToPlayStoreStrategy() {
        val res = ContentPriorityResolver().resolve(
            listOf(textAttachment("great app https://play.google.com/store/apps/details?id=com.x"))
        )
        assertEquals(OpenStrategy.OPEN_PLAY_STORE, res.recommendedOpenStrategy)
    }

    @Test
    fun plainTextWithoutUrlIsTextType() {
        val res = ContentPriorityResolver().resolve(listOf(textAttachment("a grocery list, no links")))
        assertEquals(ItemType.TEXT, res.previewType)
        assertEquals(OpenStrategy.OPEN_FILE, res.recommendedOpenStrategy)
    }

    private fun fileAttachment(mime: String, name: String): IngestionAttachment = IngestionAttachment(
        id = UUID.randomUUID().toString(),
        sessionId = "s",
        status = AttachmentStatus.READY,
        errorCode = AttachmentError.NONE,
        attachmentType = "STREAM",
        originalUri = "content://media/$name",
        localPath = "/data/files/shared_imports/$name",
        mimeType = mime,
        filename = name,
        contentHash = UUID.randomUUID().toString(),
        width = null, height = null, duration = null, fileSize = 1000L,
        pageCount = null, domain = null, artist = null, album = null,
        latitude = null, longitude = null, thumbnailPath = null,
        previewTitle = name, rawExtrasJson = null,
    )

    @Test
    fun mixedShare_linkOutranksImage() {
        // A share containing both an image and a link (e.g. news article + hero image):
        // the link is the primary logical content.
        val res = ContentPriorityResolver().resolve(
            listOf(
                fileAttachment("image/jpeg", "hero.jpg"),
                textAttachment("read more https://news.example.com/story"),
            )
        )
        assertEquals("STREAM should defer to the link", ItemType.LINK, res.previewType)
        assertEquals(1, res.secondaryAttachments.size)
    }

    @Test
    fun multipleImages_firstImageIsPrimaryOthersSecondary() {
        val res = ContentPriorityResolver().resolve(
            listOf(
                fileAttachment("image/jpeg", "a.jpg"),
                fileAttachment("image/jpeg", "b.jpg"),
                fileAttachment("image/jpeg", "c.jpg"),
            )
        )
        assertTrue(res.previewType == ItemType.PHOTO || res.previewType == ItemType.SCREENSHOT)
        assertEquals(2, res.secondaryAttachments.size)
    }

    @Test
    fun pdfShare_resolvesToPdf() {
        val res = ContentPriorityResolver().resolve(listOf(fileAttachment("application/pdf", "doc.pdf")))
        assertEquals(ItemType.PDF, res.previewType)
        assertEquals(OpenStrategy.OPEN_PDF, res.recommendedOpenStrategy)
    }
}
