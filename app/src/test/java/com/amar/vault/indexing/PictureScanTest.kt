package com.amar.vault.indexing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.IndexingPipeline
import com.amar.vault.ItemRead
import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a scan coming back to the gallery does with each picture: a scan that was cut short carries
 * on from what is left, a picture read to the end is left alone, and a changed one is read again.
 * What the words "read to the end" rest on is the row: it has text, or it says it has none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PictureScanTest {

    private lateinit var db: VaultDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun close() = db.close()

    private fun picture(id: String, text: String, lang: String, parent: String? = null) = VaultItem(
        id = id, uri = "content://media/external/images/media/$id", ocrText = text, lang = lang,
        itemType = ItemType.PHOTO, timestamp = 1_000L, parentDocumentId = parent,
    )

    @Test
    fun aPictureCountsAsReadOnlyWhenItsReadingFinished() = runBlocking {
        db.vaultDao().insertAll(listOf(
            picture("with-text", "a cat on a wall", "en"),
            picture("no-text", "", IndexingPipeline.LANG_NO_TEXT),
            picture("cut-short", "", IndexingPipeline.LANG_NOT_READ),
            picture("piece", "a page of a document", "en", parent = "some-document"),
        ))
        assertEquals(setOf("with-text", "no-text"), db.vaultDao().itemsRead().map { it.id }.toSet())
    }

    @Test
    fun aPictureNotReadIsRead() {
        assertEquals(DiscoveryEngine.Decision.READ, DiscoveryEngine.decide(null, modifiedSeconds = 5))
    }

    @Test
    fun aPictureReadAndNotChangedIsLeftAlone() {
        val stored = ItemRead("id", "uri", timestamp = 10_000L)
        assertEquals(DiscoveryEngine.Decision.LEAVE, DiscoveryEngine.decide(stored, modifiedSeconds = 9))
        // Written in the same second it was stored: not a change.
        assertEquals(DiscoveryEngine.Decision.LEAVE, DiscoveryEngine.decide(stored, modifiedSeconds = 10))
    }

    @Test
    fun aPictureChangedSinceItWasReadIsReadAgain() {
        val stored = ItemRead("id", "uri", timestamp = 10_000L)
        assertEquals(DiscoveryEngine.Decision.READ_AGAIN, DiscoveryEngine.decide(stored, modifiedSeconds = 11))
    }

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun oneChangedFigureMakesAnotherPicture() {
        // Two rows of a white screen, sixteen pixels high: a digit's worth of ink differs in one block.
        val w = 160
        val a = IntArray(w * 16) { white }
        val b = a.copyOf()
        for (i in 0 until 40) b[3 * w + 20 + i % 8 + (i / 8) * w] = black
        assertFalse(SamePicture.rowsAlike(a, b, w, 16))
    }

    @Test
    fun theSamePictureSavedAgainIsTheSamePicture() {
        val w = 160
        val a = IntArray(w * 16) { i -> 0xFF000000.toInt() or ((i * 37) and 0xFF) * 0x010101 }
        assertTrue(SamePicture.rowsAlike(a, a.copyOf(), w, 16))
        // A re-save moves every value a little, nowhere much.
        val b = IntArray(a.size) { i ->
            val v = ((a[i] and 0xFF) + (if (i % 2 == 0) 3 else -3)).coerceIn(0, 255)
            0xFF000000.toInt() or v * 0x010101
        }
        assertTrue(SamePicture.rowsAlike(a, b, w, 16))
    }

    @Test
    fun aPictureFromAnotherScreenIsAnotherPicture() {
        val w = 160
        val a = IntArray(w * 16) { white }
        val b = IntArray(w * 16) { black }
        assertFalse(SamePicture.rowsAlike(a, b, w, 16))
    }

    @Test
    fun hindiIsThreeLettersInARow() {
        assertTrue(ImageContentExtractor.saysHindi("Total बैठक kal"))
        assertTrue(ImageContentExtractor.saysHindi("नमस्ते"))
        // What the Hindi reader makes of icons and of Latin digits on a screen with no Hindi on it.
        assertFalse(ImageContentExtractor.saysHindi("*।ll"))
        assertFalse(ImageContentExtractor.saysHindi("९"))
        assertFalse(ImageContentExtractor.saysHindi("৪:37 PM"))
        assertFalse(ImageContentExtractor.saysHindi("Display & touch"))
        assertFalse(ImageContentExtractor.saysHindi(""))
    }

    private fun create48MpJpegBytes(): ByteArray {
        val small = android.graphics.Bitmap.createBitmap(80, 60, android.graphics.Bitmap.Config.ARGB_8888)
        val stream = java.io.ByteArrayOutputStream()
        small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        small.recycle()
        val data = stream.toByteArray()
        for (i in 0 until data.size - 8) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xC0.toByte()) {
                data[i + 5] = 0x17.toByte() // height 6000
                data[i + 6] = 0x70.toByte()
                data[i + 7] = 0x1F.toByte() // width 8000
                data[i + 8] = 0x40.toByte()
                break
            }
        }
        return data
    }

    @Test
    fun same48MpPhotoFromTwoPathsIsRecognizedAsDuplicate() = runBlocking {
        val bytes = create48MpJpegBytes()
        // Path 1 (e.g. BulkScanWorker with decodeStreamBounded)
        val bmpPath1 = BitmapDecoding.decodeStreamBounded { java.io.ByteArrayInputStream(bytes) }!!
        assertEquals(4000, bmpPath1.width)
        assertEquals(3000, bmpPath1.height)

        val hash1 = DuplicateDetector(db.vaultDao()).hashOf(bmpPath1)
        val uriA = "file:///storage/emulated/0/DCIM/photo48.jpg"
        val itemA = VaultItem(
            id = "item_48mp_1",
            uri = uriA,
            ocrText = "sample invoice",
            itemType = ItemType.PHOTO,
            timestamp = 1000L,
            pHash = hash1,
            lang = "en"
        )
        db.vaultDao().insert(itemA)

        // Path 2 (e.g. copy arriving at a different URI)
        val bmpPath2 = BitmapDecoding.decodeStreamBounded { java.io.ByteArrayInputStream(bytes) }!!
        val hash2 = DuplicateDetector(db.vaultDao()).hashOf(bmpPath2)
        assertEquals(hash1, hash2)

        // openStored decodes via decodeStreamBounded
        val detector = DuplicateDetector(db.vaultDao()) {
            BitmapDecoding.decodeStreamBounded { java.io.ByteArrayInputStream(bytes) }
        }

        val duplicate = detector.storedCopy(bmpPath2, hash2, "file:///storage/emulated/0/Download/photo48_copy.jpg")
        assertNotNull(duplicate)
        assertEquals("item_48mp_1", duplicate!!.id)

        bmpPath1.recycle()
        bmpPath2.recycle()
    }
}
