package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One place says whether a picture is a screenshot or a photo, by what its file is called and
 * the folder it is in. The controls are what each of the places that index pictures used to
 * call the same picture.
 */
class PictureKindTest {

    private val screenshot = PictureFacts("Screenshot_20261005_180801_Settings.png", "Pictures/Screenshots/")
    private val cameraPhoto = PictureFacts("IMG_20261005_180912.jpg", "DCIM/Camera/")
    private val received = PictureFacts("IMG-20261005-WA0007.jpg", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/")

    // What the callers said, whatever the picture: these were their whole rule.
    private val bulkScanSaid = ItemType.PHOTO          // "photo", for every picture in the gallery
    private val nightlyScanSaid = ItemType.SCREENSHOT  // "screenshot", the default it never overrode

    @Test
    fun aScreenshotIsAScreenshotWhicheverPartOfTheAppFindsIt() {
        assertEquals(ItemType.SCREENSHOT, PictureKind.of(screenshot))
        assertNotEquals("the control: the bulk scan called it a photo", bulkScanSaid, PictureKind.of(screenshot))
    }

    @Test
    fun aCameraPictureIsAPhotoWhicheverPartOfTheAppFindsIt() {
        assertEquals(ItemType.PHOTO, PictureKind.of(cameraPhoto))
        assertEquals(ItemType.PHOTO, PictureKind.of(received))
        assertNotEquals("the control: the nightly scan called it a screenshot", nightlyScanSaid, PictureKind.of(cameraPhoto))
    }

    @Test
    fun theNameAloneIsEnough() {
        // A shared picture is read from a private copy; its name is all that came with it.
        for (name in listOf("Screenshot_1.png", "screenshot (3).jpg", "Screen_shot-2.png", "Screen Shot 2026-10-05 at 18.08.png", "SCREENSHOT.PNG")) {
            assertEquals(name, ItemType.SCREENSHOT, PictureKind.of(name))
        }
        for (name in listOf("IMG_0042.jpg", "poster.png", "2336", "", "screen.png", "shot.jpg")) {
            assertEquals(name, ItemType.PHOTO, PictureKind.of(name))
        }
    }

    @Test
    fun theFolderAloneIsEnough() {
        for (folder in listOf("Pictures/Screenshots/", "DCIM/Screenshots/", "Pictures/Screenshot/", "pictures/screenshots")) {
            assertEquals(folder, ItemType.SCREENSHOT, PictureKind.of("1700000000000.jpg", folder))
        }
        assertEquals(ItemType.PHOTO, PictureKind.of("1700000000000.jpg", "Download/"))
    }

    @Test
    fun whatIsAPictureAtAll() {
        assertTrue(PictureKind.isPicture("image/jpeg", "/data/user/0/app/files/shared_imports/a"))
        assertTrue(PictureKind.isPicture(null, "content://media/external/images/media/43"))
        assertTrue(PictureKind.isPicture("application/octet-stream", "/storage/emulated/0/DCIM/IMG_0001.HEIC"))
        assertTrue("its copy has no ending, its name has", PictureKind.isPicture("application/octet-stream", "/files/shared_imports/9f3", "holiday.jpg"))
        assertFalse("a link to a picture is a link", PictureKind.isPicture(null, "https://example.org/poster.png"))
        assertFalse(PictureKind.isPicture("application/pdf", "/files/shared_imports/a.pdf"))
        assertFalse(PictureKind.isPicture(null, "content://media/external/video/media/7"))
    }

    @Test
    fun aSharedPictureFileWhoseSenderDidNotSayWhatItIsIsStoredAsAPicture() {
        // Before, such a file was a "DOCUMENT" and only a guess at listing time made it a picture.
        assertEquals(ItemType.PHOTO, ItemType.fromFormer("DOCUMENT", "application/octet-stream",
            uri = "/data/user/0/com.amar.vault/files/shared_imports/9f3.jpg", sourceFile = "holiday.jpg"))
        assertEquals(ItemType.SCREENSHOT, ItemType.fromFormer("DOCUMENT", "application/octet-stream",
            uri = "/data/user/0/com.amar.vault/files/shared_imports/9f4.png", sourceFile = "Screenshot_1.png"))
    }
}
