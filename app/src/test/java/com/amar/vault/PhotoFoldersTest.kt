package com.amar.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoFoldersTest {

    private val fresh = ScanPreferences.Prefs()

    @Test
    fun screenshotsAreOnAtFirstAndOtherFoldersAreNot() {
        assertTrue(PhotoFolders.isOn(fresh, "Pictures/Screenshots/"))
        assertTrue(PhotoFolders.isOn(fresh, "DCIM/Screenshots/"))
        assertFalse(PhotoFolders.isOn(fresh, "DCIM/Camera/"))
        assertFalse(PhotoFolders.isOn(fresh, "Pictures/Trips/"))
    }

    @Test
    fun aKnownFolderTurnsOnItsOwnSwitchNotACustomFolder() {
        val on = PhotoFolders.turnedOn(fresh, "DCIM/Camera/")
        assertTrue(on.scanCamera)
        assertTrue(on.customFolders.isEmpty())
        assertTrue(PhotoFolders.isOn(on, "DCIM/Camera/"))
    }

    @Test
    fun anyOtherFolderIsKeptByItsPathAndIsScanned() {
        val on = PhotoFolders.turnedOn(fresh, "Pictures/Trips/")
        assertEquals(setOf("Pictures/Trips/"), on.customFolders)
        assertTrue(PhotoFolders.isOn(on, "Pictures/Trips/"))
        assertTrue("Pictures/Trips/" in on.foldersToScan())
        assertFalse(PhotoFolders.isOn(on, "Pictures/Recipes/"))
    }

    @Test
    fun turningAFolderOffTakesItOutAgain() {
        val on = PhotoFolders.turnedOn(fresh, "Pictures/Trips/")
        val off = PhotoFolders.turnedOff(on, "Pictures/Trips/")
        assertFalse(PhotoFolders.isOn(off, "Pictures/Trips/"))
        assertTrue(off.customFolders.isEmpty())
    }

    @Test
    fun turningOneScreenshotsFolderOffTurnsOffTheScreenshotsSwitch() {
        val off = PhotoFolders.turnedOff(fresh, "DCIM/Screenshots/")
        assertFalse(off.scanScreenshots)
        assertFalse(PhotoFolders.isOn(off, "Pictures/Screenshots/"))
    }

    @Test
    fun turningOnAFolderThatIsAlreadyOnChangesNothing() {
        assertEquals(fresh, PhotoFolders.turnedOn(fresh, "Pictures/Screenshots/"))
    }

    @Test
    fun allPhotosCoversEveryFolder() {
        val all = fresh.copy(scanAll = true)
        assertTrue(PhotoFolders.isOn(all, "Pictures/Trips/"))
        assertTrue(PhotoFolders.isOn(all, "DCIM/Camera/"))
    }

    @Test
    fun theChosenCountAndNamesComeFromThePaths() {
        val folders = listOf(
            PhotoFolders.Folder("Pictures/Screenshots/", 40),
            PhotoFolders.Folder("DCIM/Camera/", 12),
            PhotoFolders.Folder("Pictures/Trips/", 3),
        )
        assertEquals(1, PhotoFolders.chosenCount(fresh, folders))
        assertEquals("Trips", folders[2].name)
    }
}
