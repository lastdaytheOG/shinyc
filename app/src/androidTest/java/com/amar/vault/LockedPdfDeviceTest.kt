package com.amar.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.DocumentImport
import com.amar.vault.indexing.DocumentImportQueue
import com.amar.vault.indexing.DocumentImportReader
import com.amar.vault.indexing.FileToRead
import com.amar.vault.indexing.ImportFailure
import com.amar.vault.indexing.ImportLane
import com.amar.vault.indexing.ImportState
import com.amar.vault.indexing.IndexPersister
import com.amar.vault.indexing.LockedPdfCopy
import com.amar.vault.indexing.PdfPasswords
import com.amar.vault.indexing.PdfSourceReuseCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * PDFs locked with a password, each kind of lock, through the real indexer, page renderer and text
 * readers. The files are made here from plain ones, so what a locked file gives can be compared
 * with what the same file gives unlocked. Everything the test stores it removes again.
 */
@RunWith(AndroidJUnit4::class)
class LockedPdfDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = VaultDatabase.get(context)
    private val indexer = DocumentIndexer.getInstance(context)
    private val bm25: com.amar.vault.retrieval.Bm25Index = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
    ).bm25Index()
    private val folder = File(context.filesDir, "locked-test").apply { mkdirs() }
    private val added = ArrayList<String>()
    private val queue = DocumentImportQueue(db, startWorker = {})

    /** Different on every run, so that finding it on disk means this run put it there. */
    private val password = "Pw-" + UUID.randomUUID().toString().take(12)

    private val read: suspend (DocumentImport, ReadingLog) -> IndexResult = { import, log ->
        indexer.indexDocument(Uri.parse(import.uri), import.mimeType, baseId = import.id, displayName = import.name, log = log)
    }
    private val reader = DocumentImportReader(db, read)

    @Before
    fun start() {
        PDFBoxResourceLoader.init(context)
        PdfPasswords.clear()
    }

    @After
    fun cleanUp() = runBlocking {
        for (id in added) {
            val document = db.vaultDocumentDao().getById(id)
            if (document != null) {
                bm25.removeDocuments(IndexPersister(db).deletePartialByContentHash(document.contentHash))
                PdfSourceReuseCache.forget(context, document.contentHash)
            }
            db.documentImportDao().delete(id)
        }
        folder.deleteRecursively()
        PdfPasswords.clear()
    }

    private val words = listOf("marigold", "quarry", "lantern", "harbour", "walnut", "saffron")

    private fun linesOf(page: Int): List<String> {
        val word = words[(page - 1) % words.size]
        return listOf(
            "Statement of the $word account", "Sheet number $page of the register",
            "The $word balance was received and", "counted on the fourth working day",
            "Carried forward to sheet ${page + 1}",
        )
    }

    /** A PDF of [pages] pages: real text, or with [scanned] a picture of it and no text layer. */
    private fun plainPdf(name: String, pages: Int, scanned: Boolean = false): File {
        val file = File(folder, name)
        val document = PdfDocument()
        for (number in 1..pages) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            if (scanned) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
                val picture = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
                Canvas(picture).apply {
                    drawColor(Color.WHITE)
                    linesOf(number).forEachIndexed { i, line -> drawText(line, 110f, 260f + i * 130f, paint) }
                }
                page.canvas.drawBitmap(picture, null, Rect(0, 0, 595, 842), null)
                picture.recycle()
            } else {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f }
                repeat(6) { round ->
                    linesOf(number).forEachIndexed { i, line -> page.canvas.drawText(line, 40f, 60f + (round * 5 + i) * 16f, paint) }
                }
            }
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
        return file
    }

    private fun lock(
        plain: File, name: String, keyBits: Int = 128, aes: Boolean = true,
        user: String = password, owner: String = "owner-$password", canCopy: Boolean = true,
    ): File {
        val locked = File(folder, name)
        PDDocument.load(plain).use { document ->
            val rights = AccessPermission().apply { setCanExtractContent(canCopy) }
            document.protect(StandardProtectionPolicy(owner, user, rights).apply {
                encryptionKeyLength = keyBits
                setPreferAES(aes)
            })
            document.save(locked)
        }
        return locked
    }

    private suspend fun add(file: File): DocumentImport =
        queue.addPicked(listOf(FileToRead(Uri.fromFile(file), "application/pdf", file.name))).single().also { added += it.id }

    private suspend fun row(id: String) = db.documentImportDao().getById(id)!!

    private suspend fun pagesOf(id: String): List<Pair<Int, String>> =
        db.vaultDao().getAll().filter { it.parentDocumentId == id && it.ocrText.isNotBlank() }
            .sortedBy { it.chunkIndex }.map { it.pageNum to it.ocrText }

    @Test
    fun everyKindOfLockAsksWhenLockedRefusesAWrongPasswordAndReadsLikeAnUnlockedFile() = runBlocking {
        val plain = plainPdf("plain.pdf", pages = 3)
        val plainRow = add(plain)
        reader.readAll()
        val expected = pagesOf(plainRow.id)
        assertEquals((1..3).toList(), expected.map { it.first }.distinct())

        for ((bits, aes) in listOf(40 to false, 128 to false, 128 to true, 256 to true)) {
            val what = "$bits-bit ${if (aes) "AES" else "RC4"}"
            val import = add(lock(plain, "locked-$bits-$aes.pdf", bits, aes))

            reader.readAll()
            val asked = row(import.id)
            assertEquals(what, ImportState.FAILED, asked.state)
            assertEquals(what, ImportFailure.LOCKED, asked.failure)
            assertEquals(what, "This PDF is locked with a password.", com.amar.vault.indexing.ImportWords.progress(asked))
            assertTrue("$what: can be found by its name meanwhile", db.vaultDocumentDao().getById(import.id) != null)

            queue.unlock(import.id, "not the password")
            reader.readAll()
            assertEquals(what, "That password did not open this PDF.", com.amar.vault.indexing.ImportWords.progress(row(import.id)))

            queue.unlock(import.id, password)
            reader.readAll()
            assertEquals(what, ImportState.DONE, row(import.id).state)
            assertEquals("$what: page for page what the unlocked file gives", expected, pagesOf(import.id))
            assertTrue(what, bm25.search("marigold", 500).any { it.startsWith(import.id) })
        }
    }

    @Test
    fun aLockedScanIsReadOffItsPictures() = runBlocking {
        val plain = plainPdf("scan-plain.pdf", pages = 2, scanned = true)
        val import = add(lock(plain, "scan-locked.pdf"))
        reader.readAll()
        assertEquals(ImportFailure.LOCKED, row(import.id).failure)

        queue.unlock(import.id, password)
        reader.readAll()

        assertEquals(ImportState.DONE, row(import.id).state)
        val text = pagesOf(import.id)
        assertTrue("$text", text.any { it.first == 1 && "marigold" in it.second.lowercase() })
        assertTrue("$text", text.any { it.first == 2 && "quarry" in it.second.lowercase() })
    }

    @Test
    fun thePasswordIsWrittenNowhere() = runBlocking {
        val import = add(lock(plainPdf("secret-plain.pdf", pages = 2), "secret-locked.pdf"))
        reader.readAll()
        queue.unlock(import.id, "wrong-$password")
        reader.readAll()
        queue.unlock(import.id, password)
        reader.readAll()
        assertEquals(ImportState.DONE, row(import.id).state)
        // The viewer finds it by what is in the file, whatever address the file reaches it under.
        val fingerprint = PdfSourceReuseCache.fingerprint(context, Uri.parse(import.uri))!!
        assertEquals("the viewer may use it while the app runs", password, PdfPasswords.forViewing(fingerprint))
        // What is written to disk comes to rest, wal included.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }

        val forms = listOf(password.toByteArray(Charsets.UTF_8), password.toByteArray(Charsets.UTF_16LE))
        var looked = 0
        context.dataDir.walkTopDown()
            .filter { it.isFile && it.length() in 1 until (64L shl 20) }
            .filterNot { it.startsWith(folder) } // the test's own files
            .forEach { file ->
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
                looked++
                for (form in forms) assertFalse("found in ${file.path}", bytes.contains(form))
            }
        assertTrue("the files were looked at: $looked", looked > 5)
    }

    private fun ByteArray.contains(wanted: ByteArray): Boolean {
        if (wanted.isEmpty() || size < wanted.size) return false
        outer@ for (i in 0..size - wanted.size) {
            for (j in wanted.indices) if (this[i + j] != wanted[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun theViewerGetsACopyItCanOpenThatIsGoneOnceItIsOpen() {
        val locked = lock(plainPdf("view-plain.pdf", pages = 2), "view-locked.pdf")
        val uri = Uri.fromFile(locked)

        // What the viewer starts from: the file opens, and the page renderer refuses it.
        val direct = ParcelFileDescriptor.open(locked, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(direct).close()
            fail("the page renderer opened a locked PDF")
        } catch (expected: SecurityException) {
            direct.close()
        }

        assertNull("a wrong password makes no copy", LockedPdfCopy.make(context, uri, "not the password"))
        val unlockedFolder = File(context.cacheDir, "unlocked-pdf")
        assertEquals(0, unlockedFolder.listFiles().orEmpty().size)

        val copy = LockedPdfCopy.make(context, uri, password)
        assertNotNull(copy)
        val open = ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
        assertTrue(copy!!.delete())
        assertEquals("nothing is left on disk", 0, unlockedFolder.listFiles().orEmpty().size)

        PdfRenderer(open).use { renderer ->
            assertEquals(2, renderer.pageCount)
            renderer.openPage(1).use { page ->
                val picture = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                picture.eraseColor(Color.WHITE)
                page.render(picture, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                var dark = 0
                for (y in 0 until picture.height step 2) for (x in 0 until picture.width step 2) {
                    if (Color.red(picture.getPixel(x, y)) < 128) dark++
                }
                assertTrue("the page was drawn after the file was deleted: $dark dark pixels", dark > 50)
            }
        }
    }

    @Test
    fun aFileThatOnlyForbidsCopyingOpensWithoutAPassword() = runBlocking {
        val plain = plainPdf("nocopy-plain.pdf", pages = 2)
        val plainRow = add(plain)
        reader.readAll()
        val locked = lock(plain, "nocopy-locked.pdf", user = "", canCopy = false)
        val import = add(locked)

        reader.readAll()

        assertEquals("${row(import.id).failure} ${row(import.id).failureDetail}", ImportState.DONE, row(import.id).state)
        assertEquals(pagesOf(plainRow.id), pagesOf(import.id))
        ParcelFileDescriptor.open(locked, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { assertEquals("the viewer opens it as it is", 2, it.pageCount) }
        }
    }

    @Test
    fun aLongLockedScanWhosePasswordWasLostAsksAgainAndCarriesOnFromItsPage() = runBlocking {
        val plain = plainPdf("long-plain.pdf", pages = 6, scanned = true)
        val plainRow = add(plain)
        reader.readAll()
        val expected = pagesOf(plainRow.id)

        val import = add(lock(plain, "long-locked.pdf"))
        reader.readAll()
        queue.unlock(import.id, password)
        // Time runs out at once, so it stops as soon as a page is finished; the password stays for the rest.
        DocumentImportReader(db, read, readAtOnceMs = 0).readAll()
        val paused = row(import.id)
        assertTrue("waits for the charger", paused.waitsForCharger)
        assertTrue(paused.pagesDone in 1..5)
        assertEquals(password, PdfPasswords.of(import.id))

        // The app's process ended, and the password with it.
        PdfPasswords.clear()
        reader.readAll(lane = ImportLane.ON_CHARGE)
        val asked = row(import.id)
        assertEquals(ImportFailure.LOCKED, asked.failure)
        assertEquals("nothing was wrong with what was typed before", "This PDF is locked with a password.", com.amar.vault.indexing.ImportWords.progress(asked))
        assertEquals("what was read stays", paused.pagesDone, asked.pagesDone)

        queue.unlock(import.id, password)
        reader.readAll()
        assertEquals(ImportState.DONE, row(import.id).state)
        assertEquals("page for page what the unlocked file gives", expected, pagesOf(import.id))
    }
}
