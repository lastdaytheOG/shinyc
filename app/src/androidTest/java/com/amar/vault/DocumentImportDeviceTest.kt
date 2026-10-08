package com.amar.vault

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.DocumentImport
import com.amar.vault.indexing.DocumentImportReader
import com.amar.vault.indexing.FileToRead
import com.amar.vault.indexing.DocumentImportQueue
import com.amar.vault.indexing.ImportFailure
import com.amar.vault.indexing.ImportState
import com.amar.vault.indexing.IndexPersister
import com.amar.vault.indexing.PdfSourceReuseCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A scanned PDF is read by the real indexer, with the real page renderer and the real text
 * readers, through the list of imports — cut short part-way as the system cuts background
 * work short, and started again.
 *
 * The file is drawn here, a picture to a page with no text layer, so every page has to be
 * read off its picture. Everything the test stores it removes again.
 */
@RunWith(AndroidJUnit4::class)
class DocumentImportDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = VaultDatabase.get(context)
    private val indexer = DocumentIndexer.getInstance(context)
    private val bm25: com.amar.vault.retrieval.Bm25Index = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
    ).bm25Index()
    private val folder = File(context.filesDir, "import-test").apply { mkdirs() }
    private val madeHere = ArrayList<File>()

    private val pageWords = listOf(
        "marigold", "quarry", "lantern", "harbour", "walnut", "saffron", "glacier", "orchard", "thimble", "compass",
    )

    /** What page [number] says: its own word several times over, so a page is told from every other. */
    private fun linesOf(number: Int): List<String> {
        val word = pageWords[(number - 1) % pageWords.size]
        return listOf(
            "Ledger of the $word account",
            "Sheet number $number of the register",
            "The $word shipment was received and",
            "counted on the fourth working day",
            "Carried forward to sheet ${number + 1}",
        )
    }

    /** A PDF of [pages] pages, each a picture of typed lines and nothing else. */
    private fun scannedPdf(name: String, pages: Int): File {
        val file = File(folder, name)
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
        for (number in 1..pages) {
            val picture = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
            Canvas(picture).apply {
                drawColor(Color.WHITE)
                linesOf(number).forEachIndexed { i, line -> drawText(line, 110f, 260f + i * 130f, paint) }
            }
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            page.canvas.drawBitmap(picture, null, Rect(0, 0, 595, 842), null)
            document.finishPage(page)
            picture.recycle()
        }
        file.outputStream().use(document::writeTo)
        document.close()
        madeHere += file
        return file
    }

    private val reader = DocumentImportReader(db, read = { import, log ->
        indexer.indexDocument(Uri.parse(import.uri), import.mimeType, baseId = import.id, displayName = import.name, log = log)
    })

    private suspend fun add(file: File): DocumentImport =
        DocumentImportQueue(db, startWorker = {}).addPicked(listOf(FileToRead(Uri.fromFile(file), "application/pdf", file.name))).single()

    private suspend fun row(id: String) = db.documentImportDao().getById(id)!!

    private data class Piece(val page: Int, val index: Int, val text: String)

    private suspend fun pieces(documentId: String): List<Piece> =
        db.vaultDao().getAll().filter { it.parentDocumentId == documentId }
            .sortedBy { it.chunkIndex }.map { Piece(it.pageNum, it.chunkIndex, it.ocrText) }

    /** Takes everything stored of [id] out again: rows, the engine's entries, the record, the list. */
    private suspend fun forget(id: String) {
        val document = db.vaultDocumentDao().getById(id)
        if (document != null) {
            bm25.removeDocuments(IndexPersister(db).deletePartialByContentHash(document.contentHash))
            PdfSourceReuseCache.forget(context, document.contentHash)
        }
        db.documentImportDao().delete(id)
    }

    private val added = ArrayList<String>()

    @After
    fun cleanUp() = runBlocking {
        added.forEach { forget(it) }
        madeHere.forEach { it.delete() }
    }

    /**
     * Reads what is on the list and stops it — the way the system stops work that has had its
     * time — as soon as [pages] pages of [id] are done. Returns the row as it was left.
     */
    private suspend fun readUntil(id: String, pages: Int): DocumentImport {
        val reached = CompletableDeferred<Unit>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            reader.readAll { row -> if (row.id == id && row.pagesDone >= pages) reached.complete(Unit) }
        }
        withTimeout(180_000) { reached.await() }
        job.cancelAndJoin()
        return row(id)
    }

    @Test
    fun aScanCutShortIsCarriedOnAndEndsAsIfReadInOneGo() = runBlocking {
        val file = scannedPdf("ledger-cut-short.pdf", pages = 8)
        val import = add(file).also { added += it.id }

        // First stretch: stopped once two pages are done.
        val stopped = readUntil(import.id, pages = 2)
        Log.i(TAG, "stopped at page ${stopped.pagesDone} of ${stopped.pageCount}, ${stopped.pieces} pieces")
        assertEquals("still on the list, to be carried on", ImportState.READING, stopped.state)
        assertEquals(8, stopped.pageCount)
        assertTrue("stopped part-way: page ${stopped.pagesDone}", stopped.pagesDone in 2..7)
        val keptPages = pieces(import.id).filter { it.page <= stopped.pagesDone }
        assertTrue("what was read is stored", keptPages.isNotEmpty())
        assertTrue("…and can be searched already", bm25.search("marigold", 50).any { it.startsWith(import.id) })

        // Second stretch: started again, with nothing but what is written down.
        val seen = ArrayList<Int>()
        reader.readAll { row -> if (row.id == import.id) synchronized(seen) { seen += row.pagesDone } }

        val done = row(import.id)
        assertEquals(ImportState.DONE, done.state)
        assertEquals(8, done.pagesDone)
        assertEquals("it began where it had stopped, not at page 1", stopped.pagesDone, seen.min())
        val carriedOn = pieces(import.id)
        assertEquals("the pages of the first stretch were not read again", keptPages, carriedOn.filter { it.page <= stopped.pagesDone })
        assertEquals("no page is stored twice", carriedOn.size, carriedOn.map { it.index }.toSet().size)
        assertEquals("every page is there", (1..8).toList(), carriedOn.map { it.page }.distinct())
        for (page in 1..8) {
            val word = pageWords[page - 1]
            assertTrue("page $page says $word", carriedOn.any { it.page == page && word in it.text.lowercase() })
        }

        // The same file read in one go, to compare with.
        forget(import.id)
        val again = add(file).also { added += it.id }
        reader.readAll()
        assertEquals(ImportState.DONE, row(again.id).state)
        assertEquals("page for page and piece for piece the same", carriedOn, pieces(again.id))
    }

    @Test
    fun withNoMemoryOfWhereItWasAReadingBeginsAtPageOneAgain() = runBlocking {
        // The control: the indexer as every caller used it before — no log, so nothing to carry on from.
        val file = scannedPdf("ledger-from-the-start.pdf", pages = 6)
        val import = add(file).also { added += it.id }
        val stopped = readUntil(import.id, pages = 2)
        assertTrue(stopped.pagesDone in 2..5)
        val before = pieces(import.id).size
        assertTrue(before > 0)

        val uri = Uri.parse(import.uri)
        val began = CompletableDeferred<Int>()
        val job = CoroutineScope(Dispatchers.Default).launch {
            indexer.indexDocument(uri, "application/pdf", baseId = import.id, displayName = import.name, log = object : ReadingLog {
                override suspend fun pagesDone(fingerprint: String?) = 0
                override suspend fun reading(fingerprint: String?, pagesDone: Int, pieces: Int) {
                    began.complete(pieces(import.id).size)
                }
                override suspend fun pageCount(pages: Int) = Unit
                override suspend fun pageDone(page: Int, pieces: Int) = Unit
            })
        }
        val storedWhenItBegan = withTimeout(60_000) { began.await() }
        job.cancelAndJoin()

        assertEquals("everything read in the first stretch was thrown away", 0, storedWhenItBegan)
    }

    @Test
    fun aFileThatIsNotAPdfSaysSoInWords() = runBlocking {
        val file = File(folder, "broken.pdf").apply { writeText("%PDF-1.4\nthis is where the file was cut off") }
        madeHere += file
        val import = add(file).also { added += it.id }

        reader.readAll()

        val row = row(import.id)
        assertEquals(ImportState.FAILED, row.state)
        assertEquals("${row.failureDetail}", ImportFailure.DAMAGED, row.failure)
    }

    @Test
    fun aPdfCutOffPartWayIsSaidToBeDamaged() = runBlocking {
        // A download that stopped early. PDFBox opens it without complaint and finds no page;
        // before, that was reported as a file with no words in it.
        val whole = scannedPdf("whole.pdf", pages = 2).readBytes()
        val file = File(folder, "cut-off.pdf").apply { writeBytes(whole.copyOf(whole.size / 3)) }
        madeHere += file
        val import = add(file).also { added += it.id }

        reader.readAll()

        assertEquals("${row(import.id).failureDetail}", ImportFailure.DAMAGED, row(import.id).failure)
        assertEquals(
            "The file is damaged or incomplete, so it could not be read.",
            com.amar.vault.indexing.ImportWords.progress(row(import.id)),
        )
    }

    @Test
    fun aFileThatHasGoneSaysSoInWords() = runBlocking {
        val file = scannedPdf("moved-away.pdf", pages = 1)
        val import = add(file).also { added += it.id }
        file.delete()

        reader.readAll()

        assertEquals(ImportFailure.FILE_GONE, row(import.id).failure)
    }

    private companion object { const val TAG = "DocumentImportTest" }
}
