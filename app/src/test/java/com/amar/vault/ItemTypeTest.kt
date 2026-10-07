package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One list of item types, and how every name that was stored before there was one is read.
 * The version-14 migration and the chat history's stored sources both go through
 * [ItemType.fromFormer], so what is pinned here is what happens to a vault that is upgraded.
 */
class ItemTypeTest {

    @Test
    fun theStoredNamesAreTheListInLowerCaseEachOnce() {
        assertEquals(
            listOf("pdf", "word", "excel", "epub", "screenshot", "photo", "video", "audio", "link", "text", "file"),
            ItemType.entries.map { it.stored },
        )
        for (type in ItemType.entries) assertEquals(type, ItemType.ofStored(type.stored))
    }

    @Test
    fun aNameThatIsNotOnTheListIsNotAStoredName() {
        // Capitals included: the column holds the name exactly.
        for (name in listOf("PDF", "Pdf", "dev_manual", "camera", "YOUTUBE", "document", "")) {
            assertNull(name, ItemType.ofStored(name))
        }
    }

    @Test
    fun everyNameTheIndexersWroteIsRead() {
        val was = mapOf(
            // Documents: the indexer wrote lower case, a shared file capitals.
            "pdf" to ItemType.PDF, "PDF" to ItemType.PDF,
            "word" to ItemType.WORD, "excel" to ItemType.EXCEL, "epub" to ItemType.EPUB,
            // Pictures: named after the folder, or after the screen that indexed them.
            "screenshot" to ItemType.SCREENSHOT, "SCREENSHOT" to ItemType.SCREENSHOT,
            "photo" to ItemType.PHOTO, "PHOTO" to ItemType.PHOTO,
            "camera" to ItemType.PHOTO, "whatsapp" to ItemType.PHOTO, "dev_manual" to ItemType.PHOTO,
            "image" to ItemType.PHOTO, "IMAGE" to ItemType.PHOTO,
            // Saved links: named after the site.
            "YOUTUBE" to ItemType.LINK, "REDDIT" to ItemType.LINK, "ARTICLE" to ItemType.LINK, "LINK" to ItemType.LINK,
            "TEXT" to ItemType.TEXT, "VIDEO" to ItemType.VIDEO, "AUDIO" to ItemType.AUDIO,
        )
        for ((name, type) in was) assertEquals(name, type, ItemType.fromFormer(name))
    }

    @Test
    fun aSharedPictureCalledImageIsAScreenshotWhenItsFileSaysSo() {
        assertEquals(ItemType.SCREENSHOT, ItemType.fromFormer("IMAGE", sourceFile = "Screenshot_20261005_180801.png"))
        assertEquals(ItemType.PHOTO, ItemType.fromFormer("IMAGE", sourceFile = "IMG_0042.jpg"))
    }

    @Test
    fun aSharedFileCalledDocumentIsWhatItsTypeOrNameSays() {
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val xlsx = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        assertEquals(ItemType.WORD, ItemType.fromFormer("DOCUMENT", mimeType = docx))
        assertEquals(ItemType.EXCEL, ItemType.fromFormer("DOCUMENT", mimeType = xlsx))
        assertEquals(ItemType.EPUB, ItemType.fromFormer("DOCUMENT", mimeType = "application/epub+zip"))
        // A provider that does not know the type says "octet-stream"; the name still tells.
        assertEquals(ItemType.PDF, ItemType.fromFormer("DOCUMENT", "application/octet-stream", sourceFile = "Bill.pdf"))
        assertEquals(ItemType.WORD, ItemType.fromFormer("DOCUMENT", "application/octet-stream",
            uri = "/data/user/0/com.amar.vault/files/shared_imports/9f.docx"))
        assertEquals("nothing to go by", ItemType.FILE, ItemType.fromFormer("DOCUMENT", "application/zip", sourceFile = "backup.zip"))
        assertEquals(ItemType.FILE, ItemType.fromFormer("DOCUMENT"))
    }

    @Test
    fun aNameNoVersionWroteIsReadFromWhatTheRowSays() {
        assertEquals(ItemType.PHOTO, ItemType.fromFormer("telegram", uri = "content://media/external/images/media/43"))
        assertEquals(ItemType.SCREENSHOT, ItemType.fromFormer("telegram", "image/png", sourceFile = "Screenshot_1.png"))
        assertEquals(ItemType.LINK, ItemType.fromFormer("bookmark", uri = "https://example.org/a"))
        assertEquals(ItemType.VIDEO, ItemType.fromFormer("clip", mimeType = "video/mp4"))
        assertEquals(ItemType.TEXT, ItemType.fromFormer("memo", uri = "share://text/1"))
        assertEquals(ItemType.FILE, ItemType.fromFormer("benchmark_probe", uri = "benchmark://probe"))
        // A link to a picture is a link.
        assertEquals(ItemType.LINK, ItemType.fromFormer("LINK", uri = "https://example.org/poster.png"))
    }

    @Test
    fun theDatabaseKeepsTheNameAndReadsAStrayOneWithoutFailing() {
        val converter = ItemTypeConverter()
        for (type in ItemType.entries) assertEquals(type, converter.fromStored(converter.toStored(type)))
        assertEquals(ItemType.PDF, converter.fromStored("PDF"))
        assertEquals(ItemType.FILE, converter.fromStored("something else entirely"))
    }

    @Test
    fun documentsAndPicturesAreKnownByType() {
        assertEquals(setOf(ItemType.PDF, ItemType.WORD, ItemType.EXCEL, ItemType.EPUB), ItemType.entries.filter { it.isDocument }.toSet())
        assertEquals(setOf(ItemType.SCREENSHOT, ItemType.PHOTO), ItemType.entries.filter { it.isImage }.toSet())
        assertTrue(ItemType.entries.none { it.isDocument && it.isImage })
    }

    @Test
    fun aLinksSiteIsToldFromItsAddress() {
        assertEquals(LinkSite.YOUTUBE, LinkSite.of("https://youtu.be/abc"))
        assertEquals(LinkSite.YOUTUBE, LinkSite.of("https://www.YouTube.com/watch?v=abc"))
        assertEquals(LinkSite.REDDIT, LinkSite.of("https://www.reddit.com/r/india/comments/1"))
        assertEquals(LinkSite.OTHER, LinkSite.of("https://example.org/a-long-read"))
    }
}
