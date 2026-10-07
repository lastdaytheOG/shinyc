package com.amar.vault.indexing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.ItemType
import com.amar.vault.PictureFacts
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import com.amar.vault.VaultMetadata
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pictures already in the vault are given the type the one rule gives them, whichever
 * part of the app had named them before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PictureTypeUpkeepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: VaultDatabase

    /** The gallery as the phone has it: picture id → what it is called and where it is. */
    private val gallery = mutableMapOf(
        1L to PictureFacts("Screenshot_20261005_180801_Settings.png", "Pictures/Screenshots/"),
        2L to PictureFacts("IMG_20261005_180912.jpg", "DCIM/Camera/"),
        3L to PictureFacts("Screenshot_20261005_180912_Music.png", "Pictures/Screenshots/"),
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun upkeep() = PictureTypeUpkeep(db, context) { gallery }

    private fun picture(id: String, type: ItemType, uri: String, name: String = "", mime: String? = null) =
        VaultItem(id = id, uri = uri, ocrText = "read off $id", lang = "en", itemType = type, sourceFile = name, timestamp = 3L, mimeType = mime)

    private fun inGallery(id: String, type: ItemType, galleryId: Long) =
        picture(id, type, "content://media/external/images/media/$galleryId", name = galleryId.toString())

    private suspend fun typeOf(id: String) = db.vaultDao().getByIds(listOf(id)).single().itemType

    @Test
    fun aPictureIsWhatItsFileAndFolderSayNotWhatIndexedIt() = runBlocking {
        db.vaultDao().insertAll(listOf(
            inGallery("fromBulkScan", ItemType.PHOTO, 1),         // a screenshot the bulk scan called a photo
            inGallery("fromNightlyScan", ItemType.SCREENSHOT, 2), // a camera picture the nightly scan called a screenshot
            inGallery("alreadyRight", ItemType.SCREENSHOT, 3),
        ))
        val told = mutableListOf<String>()
        val did = assertNotNullAnd(upkeep().retypeIfRuleChanged { told += it })

        assertEquals(ItemType.SCREENSHOT, typeOf("fromBulkScan"))
        assertEquals(ItemType.PHOTO, typeOf("fromNightlyScan"))
        assertEquals(ItemType.SCREENSHOT, typeOf("alreadyRight"))
        assertEquals(mapOf("photo → screenshot" to 1, "screenshot → photo" to 1), did.changed)
        assertEquals(3, did.picturesChecked)
        assertEquals("whoever indexes the type is told what changed", listOf("fromBulkScan", "fromNightlyScan"), told.sorted())
    }

    @Test
    fun onlyTheTypeChangesAndTheFactThatRepeatsIt() = runBlocking {
        val before = inGallery("shot", ItemType.PHOTO, 1)
        db.vaultDao().insert(before)
        db.vaultMetadataDao().insertAll(listOf(
            VaultMetadata(vaultItemId = "shot", type = "SOURCE_TYPE", value = "PHOTO", confidence = 1f, source = "system", extractionVersion = "v3"),
            VaultMetadata(vaultItemId = "shot", type = "AMOUNT", value = "250", numericValue = 250.0, confidence = 0.8f, source = "regex", extractionVersion = "v2"),
        ))
        upkeep().retypeAll()

        assertEquals(before.copy(itemType = ItemType.SCREENSHOT), db.vaultDao().getByIds(listOf("shot")).single())
        assertEquals(mapOf("SOURCE_TYPE" to "SCREENSHOT", "AMOUNT" to "250"),
            db.vaultMetadataDao().getByItemId("shot").associate { it.type to it.value })
    }

    @Test
    fun aPictureKeptAsAFileIsToldByItsName() = runBlocking {
        db.vaultDao().insertAll(listOf(
            picture("shared", ItemType.PHOTO, "/data/user/0/app/files/shared_imports/9f3.png", name = "Screenshot_1.png", mime = "image/png"),
            picture("sharedPhoto", ItemType.PHOTO, "/data/user/0/app/files/shared_imports/9f4.jpg", name = "holiday.jpg", mime = "image/jpeg"),
            // Stored as a plain file because its sender did not say it was a picture.
            picture("untyped", ItemType.FILE, "/data/user/0/app/files/shared_imports/9f5.jpg", name = "holiday2.jpg", mime = "application/octet-stream"),
        ))
        upkeep().retypeAll()
        assertEquals(ItemType.SCREENSHOT, typeOf("shared"))
        assertEquals(ItemType.PHOTO, typeOf("sharedPhoto"))
        assertEquals("it is a picture, and is now stored as one", ItemType.PHOTO, typeOf("untyped"))
    }

    @Test
    fun withNothingToTellByAPictureKeepsItsType() = runBlocking {
        db.vaultDao().insertAll(listOf(
            inGallery("deleted", ItemType.SCREENSHOT, 99),   // the gallery no longer has it
            // Named a screenshot when it was indexed, by a name the vault did not keep.
            picture("picked", ItemType.SCREENSHOT, "content://com.android.providers.media.documents/document/image%3A7", name = "image%3A7", mime = "image/png"),
        ))
        val did = upkeep().retypeAll()
        assertEquals(ItemType.SCREENSHOT, typeOf("deleted"))
        assertEquals(ItemType.SCREENSHOT, typeOf("picked"))
        assertEquals(0, did.changedCount)
        assertEquals(1, did.notInGallery)
    }

    @Test
    fun whatIsNotAPictureIsNotTouched() = runBlocking {
        val rows = listOf(
            picture("link", ItemType.LINK, "https://example.org/Screenshot_poster.png"),
            picture("savedPdf", ItemType.PDF, "/files/shared_imports/Screenshot_scan.pdf", name = "Screenshot_scan.pdf", mime = "application/pdf"),
            picture("note", ItemType.TEXT, "share://text/1"),
            VaultItem(id = "page_chunk0", uri = "content://docs/page", ocrText = "a page", lang = "en", itemType = ItemType.PDF,
                sourceFile = "Screenshots.pdf", timestamp = 1L, parentDocumentId = "page"),
        )
        db.vaultDao().insertAll(rows)
        val did = upkeep().retypeAll()
        assertEquals(0, did.picturesChecked)
        assertEquals(rows.map { it.itemType }, rows.map { typeOf(it.id) })
    }

    @Test
    fun itIsDoneOncePerVersionOfTheRule() = runBlocking {
        db.vaultDao().insert(inGallery("shot", ItemType.PHOTO, 1))
        assertNotNull(upkeep().retypeIfRuleChanged())
        assertNull(upkeep().retypeIfRuleChanged())
        assertEquals(0, upkeep().retypeAll().changedCount)
    }

    @Test
    fun whenTheGalleryCannotBeAskedItIsTriedAgainNextTime() = runBlocking {
        // The app may not yet be allowed to see the gallery: it answers with nothing.
        db.vaultDao().insert(inGallery("shot", ItemType.PHOTO, 1))
        val blind = PictureTypeUpkeep(db, context) { emptyMap() }
        val did = assertNotNullAnd(blind.retypeIfRuleChanged())
        assertEquals(ItemType.PHOTO, typeOf("shot"))
        assertEquals(1, did.notInGallery)

        // Allowed now: the same picture is looked at again and put right.
        assertNotNull(upkeep().retypeIfRuleChanged())
        assertEquals(ItemType.SCREENSHOT, typeOf("shot"))
    }

    @Test
    fun whatItDidCanBeReadBack() = runBlocking {
        assertNull(PictureTypeUpkeep.lastReport(context))
        db.vaultDao().insert(inGallery("shot", ItemType.PHOTO, 1))
        upkeep().retypeAll()
        val (_, summary) = assertNotNullAnd(PictureTypeUpkeep.lastReport(context))
        assertTrue(summary, summary.contains("1 pictures checked; 1 renamed (photo → screenshot: 1)"))
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T { assertNotNull(value); return value!! }
}
