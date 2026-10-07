package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each chip above the search results lets through. */
class SearchFilterTest {

    private fun item(type: String, uri: String = "content://x/1", text: String = "", mime: String? = null, parent: String? = null) =
        VaultItem(id = "i", uri = uri, ocrText = text, lang = "en", itemType = type, timestamp = 0L,
            mimeType = mime, parentDocumentId = parent)

    private val photo = item("photo", text = "paid ₹ 250 at the location shown")
    private val screenshot = item("screenshot", text = "18:09 ELLA CIAO")
    private val sharedPhoto = item("PHOTO", uri = "/data/user/0/app/files/shared_imports/a.jpg", mime = "image/jpeg")
    // The gallery scan names a picture after the folder it was found in.
    private val cameraPhoto = item("camera", uri = "content://media/external/images/media/41")
    private val whatsAppPicture = item("whatsapp", uri = "content://media/external/images/media/42")
    private val pictureOfAnUnlistedType = item("telegram", uri = "content://media/external/images/media/43")
    private val pictureFile = item("FILE", uri = "/storage/emulated/0/DCIM/IMG_0001.HEIC")
    private val linkToAPicture = item("LINK", uri = "https://example.org/poster.png")
    private val pdfPage = item("pdf", text = "fine of ₹ 50000 and the distance travelled", parent = "doc")
    private val sharedPdf = item("PDF", uri = "/data/user/0/app/files/shared_imports/a.pdf", mime = "application/pdf")
    private val wordPage = item("word", text = "notes", parent = "doc2")
    private val video = item("YOUTUBE", uri = "https://youtu.be/abc")
    private val article = item("LINK", uri = "https://example.org/a-long-read")
    private val product = item("LINK", uri = "https://www.amazon.in/dp/B0")
    private val song = item("LINK", uri = "https://open.spotify.com/track/1")

    private fun let(chip: String, item: VaultItem, saved: StashItemWithVaultItem? = null) =
        SearchFilter.accepts(chip, item, saved)

    @Test
    fun allLetsEverythingThrough() {
        listOf(photo, screenshot, pdfPage, video, article, product, song).forEach { assertTrue(let(SearchFilter.ALL, it)) }
    }

    @Test
    fun imagesAreThePictures() {
        listOf(photo, screenshot, sharedPhoto, cameraPhoto, whatsAppPicture, pictureOfAnUnlistedType, pictureFile)
            .forEach { assertTrue("${it.itemType} ${it.uri}", let(SearchFilter.IMAGES, it)) }
        listOf(pdfPage, sharedPdf, wordPage, video, article, product, song, linkToAPicture)
            .forEach { assertFalse(it.uri, let(SearchFilter.IMAGES, it)) }
    }

    @Test
    fun aPictureIsUnderNoOtherChip() {
        for (picture in listOf(photo, cameraPhoto, whatsAppPicture, pictureFile)) {
            for (chip in SearchFilter.CHIPS - SearchFilter.ALL - SearchFilter.IMAGES) {
                assertFalse("${picture.itemType} under $chip", let(chip, picture))
            }
        }
    }

    @Test
    fun documentsAreTheFilesAndTheirPages() {
        listOf(pdfPage, sharedPdf, wordPage).forEach { assertTrue(it.itemType, let(SearchFilter.DOCUMENTS, it)) }
        listOf(photo, screenshot, sharedPhoto, video, article, product, song).forEach { assertFalse(it.uri, let(SearchFilter.DOCUMENTS, it)) }
    }

    @Test
    fun aPictureOrADocumentWithAPriceOnItIsNotAProduct() {
        assertFalse(let(SearchFilter.PRODUCTS, photo))
        assertFalse(let(SearchFilter.PRODUCTS, pdfPage))
        assertTrue(let(SearchFilter.PRODUCTS, product))
    }

    @Test
    fun linksGoToTheirOwnChips() {
        assertTrue(let(SearchFilter.VIDEOS, video))
        assertTrue(let(SearchFilter.ARTICLES, article))
        assertTrue(let(SearchFilter.MUSIC, song))
        assertFalse(let(SearchFilter.ARTICLES, video))
        assertFalse(let(SearchFilter.VIDEOS, article))
    }

    @Test
    fun favouritesAndFoldersAreAboutTheStashEntry() {
        fun saved(favourite: Boolean, folder: String) = StashItemWithVaultItem(
            stashId = "s", sessionId = null, vaultItemId = "doc", vaultType = "SAVED", category = folder, savedAt = 0L,
            sourceApp = "", isFavorite = favourite, createdAt = 0L, userNote = null, thumbnailPath = null, uri = "",
            ocrText = "", itemType = "PDF", sourceFile = "", timestamp = 0L, title = null, mimeType = null,
        )
        assertFalse("never saved", let(SearchFilter.FAVORITES, pdfPage))
        assertFalse("never saved", let(SearchFilter.FOLDERS, pdfPage))
        assertTrue(let(SearchFilter.FAVORITES, pdfPage, saved(favourite = true, folder = "")))
        assertFalse(let(SearchFilter.FAVORITES, pdfPage, saved(favourite = false, folder = "Bills")))
        assertTrue(let(SearchFilter.FOLDERS, pdfPage, saved(favourite = false, folder = "Bills")))
        assertFalse("saved, but not put in a folder", let(SearchFilter.FOLDERS, pdfPage, saved(favourite = true, folder = "")))
    }

    @Test
    fun theChipsOnScreenAreTheOnesHandled() {
        assertEquals(
            listOf("All", "Images", "Videos", "Articles", "Products", "Music", "Documents", "Favorites", "Folders"),
            SearchFilter.CHIPS,
        )
    }
}
