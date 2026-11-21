package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Test

class PictureAddressTest {

    private val storage = "/storage/emulated/0"

    @Test
    fun aGalleryAddressStaysAsItIs() {
        val address = "content://media/external/images/media/4821"
        assertEquals(listOf(address), PictureAddress.candidates(address, storage))
    }

    @Test
    fun aPickedGalleryPictureIsAlsoReachedThroughTheGallery() {
        val address = "content://com.android.providers.media.documents/document/image%3A4821"
        assertEquals(
            listOf(address, "content://media/external/images/media/4821"),
            PictureAddress.candidates(address, storage)
        )
    }

    @Test
    fun aPickedFileIsAlsoReachedByItsPath() {
        val address = "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2Fsample%20one.jpg"
        assertEquals(
            listOf(address, "/storage/emulated/0/DCIM/Camera/sample one.jpg"),
            PictureAddress.candidates(address, storage)
        )
    }

    @Test
    fun aPictureFromAPickedFolderIsAlsoReachedByItsPath() {
        val address = "content://com.android.externalstorage.documents/tree/primary%3APictures/document/primary%3APictures%2Fsample.png"
        assertEquals(
            listOf(address, "/storage/emulated/0/Pictures/sample.png"),
            PictureAddress.candidates(address, storage)
        )
    }

    @Test
    fun aPictureOnACardIsReachedUnderItsOwnVolume() {
        val address = "content://com.android.externalstorage.documents/document/1A2B-3C4D%3APictures%2Fsample.png"
        assertEquals(
            listOf(address, "/storage/1A2B-3C4D/Pictures/sample.png"),
            PictureAddress.candidates(address, storage)
        )
    }

    @Test
    fun aDownloadedPictureIsReachedByItsPathOrItsGalleryNumber() {
        val byPath = "content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fsample.jpg"
        assertEquals(
            listOf(byPath, "/storage/emulated/0/Download/sample.jpg"),
            PictureAddress.candidates(byPath, storage)
        )
        val byNumber = "content://com.android.providers.downloads.documents/document/msf%3A77"
        assertEquals(
            listOf(byNumber, "content://media/external/images/media/77"),
            PictureAddress.candidates(byNumber, storage)
        )
    }

    @Test
    fun aPathOrAnEmptyAddressHasNoOtherRoute() {
        assertEquals(listOf("/data/user/0/app/files/sample.jpg"), PictureAddress.candidates("/data/user/0/app/files/sample.jpg", storage))
        assertEquals(emptyList<String>(), PictureAddress.candidates("  ", storage))
    }
}
