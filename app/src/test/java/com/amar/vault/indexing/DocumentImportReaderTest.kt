package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.amar.vault.IndexError
import com.amar.vault.IndexResult
import com.amar.vault.ItemType
import com.amar.vault.ReadingLog
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The list of files to read and the reader that works through it: what is written down of a
 * file before, during and after its reading, and how a reading that was cut short finds its
 * place again. The indexer is stood in for by a few lines that behave as it does toward the
 * [ReadingLog]; that the real one reads a real PDF on from the right page is
 * `DocumentImportDeviceTest`'s to show.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DocumentImportReaderTest {

    private lateinit var db: VaultDatabase
    private var started = 0
    private var clock = 1_000L
    private lateinit var queue: DocumentImportQueue

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), VaultDatabase::class.java)
            .allowMainThreadQueries().build()
        queue = DocumentImportQueue(db, startWorker = { started++ }, nameOf = { it.lastPathSegment }, now = { clock++ })
    }

    @After
    fun close() = db.close()

    private fun file(name: String, type: String = "application/pdf") = FileToRead(Uri.parse("content://docs/$name"), type)

    private suspend fun row(id: String) = db.documentImportDao().getById(id)!!

    /**
     * A scan of [pages] pages, read the way the indexer reads one: it asks where to start, says
     * so, and reports each page as it finishes it. [stopAt] cuts the reading short after that
     * page, as the system does when background work has had its ten minutes. (Cut short from
     * inside like this, the reader's call returns; what matters is what is left written down.)
     */
    private class Scan(val pages: Int, val fingerprint: String = "fp", var stopAt: Int? = null) {
        val pagesRead = ArrayList<Int>()

        suspend fun read(import: DocumentImport, log: ReadingLog): IndexResult {
            val done = log.pagesDone(fingerprint)
            log.reading(fingerprint, done, pieces = done)
            log.pageCount(pages)
            for (page in done + 1..pages) {
                pagesRead += page
                log.pageDone(page, pieces = page)
                if (page == stopAt) throw CancellationException("ten minutes are up")
            }
            return IndexResult.Success(import.name, pages, 5L)
        }
    }

    private fun reader(read: suspend (DocumentImport, ReadingLog) -> IndexResult) =
        DocumentImportReader(db, read, now = { clock++ })

    // ── What is written down ────────────────────────────────────────────────────────────

    @Test
    fun aChosenFileIsWrittenDownBeforeAnythingIsRead() = runBlocking {
        val added = queue.addPicked(listOf(file("Act.pdf"), file("Notes.docx", "")))

        assertEquals(listOf("Act.pdf", "Notes.docx"), added.map { it.name })
        assertTrue(added.all { it.state == ImportState.WAITING && it.origin == ImportOrigin.PICKED })
        assertEquals("the worker is started once for the lot", 1, started)
        assertEquals(2, db.documentImportDao().countUnfinished())
    }

    @Test
    fun aFileAlreadyWaitingIsNotAddedTwice() = runBlocking {
        queue.addPicked(listOf(file("Act.pdf")))
        assertEquals(emptyList<DocumentImport>(), queue.addPicked(listOf(file("Act.pdf"))))
        assertEquals(1, db.documentImportDao().getAll().size)

        // Once it has been read, choosing it again is a new request.
        reader { import, _ -> IndexResult.Success(import.name, 3, 1L) }.readAll()
        assertEquals(1, queue.addPicked(listOf(file("Act.pdf"))).size)
    }

    @Test
    fun aSharedDocumentIsWrittenDownUnderItsSavedItemsId() = runBlocking {
        val saved = VaultItem(
            id = "bill", uri = "/data/user/0/com.amar.vault/files/shared_imports/abc.pdf", ocrText = "", lang = "en",
            itemType = ItemType.PDF, sourceFile = "Bill.pdf", timestamp = 1L, mimeType = "application/pdf",
        )
        assertTrue(queue.addShared(saved))

        val row = row("bill")
        assertEquals(ImportOrigin.SHARED, row.origin)
        // Capture stores the copy as a bare path; the indexer opens files through an address
        // with a scheme, and handed the bare path it read nothing.
        assertEquals(Uri.fromFile(java.io.File(saved.uri)).toString(), row.uri)
        assertTrue(row.uri, row.uri.startsWith("file://") && row.uri.endsWith("abc.pdf"))
        assertEquals("Bill.pdf", row.name)
    }

    @Test
    fun aSharedFileThatIsNotADocumentIsNotQueued() = runBlocking {
        val video = VaultItem(
            id = "clip", uri = "/files/shared_imports/a.mp4", ocrText = "", lang = "en", itemType = ItemType.VIDEO,
            sourceFile = "a.mp4", timestamp = 1L, mimeType = "video/mp4",
        )
        assertFalse(queue.addShared(video))
        assertNull(db.documentImportDao().getById("clip"))
        assertEquals(0, started)
    }

    // ── How a reading ends ──────────────────────────────────────────────────────────────

    @Test
    fun eachWayAReadingEndsIsWrittenDown() = runBlocking {
        val (read, twice, locked, empty) = queue.addPicked(
            listOf(file("Read.pdf"), file("Twice.pdf"), file("Locked.pdf"), file("Blank.pdf"))
        )
        val ended = reader { import, log ->
            log.reading("fp-${import.name}", 0, 0)
            when (import.name) {
                "Read.pdf" -> IndexResult.Success(import.name, 12, 40L)
                "Twice.pdf" -> IndexResult.Duplicate(import.name, "hash")
                "Locked.pdf" -> IndexResult.Failure(import.name, IndexError.ExtractionFailed(java.io.IOException("Cannot decrypt PDF, the password is incorrect")))
                else -> IndexResult.Failure(import.name, IndexError.EmptyContent)
            }
        }.readAll()

        assertEquals(4, ended.size)
        assertEquals(ImportState.DONE to 12, row(read.id).let { it.state to it.pieces })
        assertEquals(ImportState.ALREADY_THERE, row(twice.id).state)
        assertEquals(ImportState.FAILED to ImportFailure.LOCKED, row(locked.id).let { it.state to it.failure })
        assertEquals(ImportState.FAILED to ImportFailure.NO_TEXT, row(empty.id).let { it.state to it.failure })
        assertEquals("nothing is left to read", 0, db.documentImportDao().countUnfinished())
    }

    @Test
    fun aReadingThatThrowsIsAFailureNotACrash() = runBlocking {
        val (bad, good) = queue.addPicked(listOf(file("Bad.pdf"), file("Good.pdf")))
        reader { import, _ ->
            if (import.name == "Bad.pdf") error("index is corrupt") else IndexResult.Success(import.name, 1, 1L)
        }.readAll()

        assertEquals(ImportState.FAILED, row(bad.id).state)
        assertEquals("index is corrupt", row(bad.id).failureDetail)
        assertEquals("the file after it is still read", ImportState.DONE, row(good.id).state)
    }

    // ── Cut short, and carried on ───────────────────────────────────────────────────────

    @Test
    fun aReadingCutShortIsCarriedOnFromThePageItReached() = runBlocking {
        val import = queue.addPicked(listOf(file("Scan.pdf"))).single()
        val scan = Scan(pages = 300, stopAt = 120)

        reader(scan::read).readAll()
        val stopped = row(import.id)
        assertEquals("still to be read", ImportState.READING, stopped.state)
        assertEquals(120, stopped.pagesDone)
        assertEquals(300, stopped.pageCount)
        assertEquals(1, db.documentImportDao().countUnfinished())

        // The system starts the work again: a new reader, the same list.
        scan.stopAt = null
        scan.pagesRead.clear()
        reader(scan::read).readAll()

        assertEquals("not one page is read twice", (121..300).toList(), scan.pagesRead)
        assertEquals(ImportState.DONE, row(import.id).state)
        assertEquals(300, row(import.id).pagesDone)
    }

    @Test
    fun startedFromPageOneEachTimeAScanLongerThanTenMinutesNeverFinished() = runBlocking {
        // The control: what a reading did before it had a log. Ten minutes are enough for 120
        // pages; each time the work is started again it begins at page 1, and stops at 120.
        val scan = Scan(pages = 300, stopAt = 120)
        val noMemory = object : ReadingLog {
            override suspend fun pagesDone(fingerprint: String?) = 0
            override suspend fun reading(fingerprint: String?, pagesDone: Int, pieces: Int) = Unit
            override suspend fun pageCount(pages: Int) = Unit
            override suspend fun pageDone(page: Int, pieces: Int) = Unit
        }
        val import = DocumentImport(id = "scan", uri = "content://docs/Scan.pdf", name = "Scan.pdf", mimeType = "application/pdf",
            origin = ImportOrigin.SHARED, state = ImportState.WAITING, addedAt = 1L)
        repeat(5) {
            try {
                scan.read(import, noMemory)
                fail("it cannot finish")
            } catch (_: CancellationException) {
            }
        }
        assertEquals("five starts, and never a page past 120", 120, scan.pagesRead.max())
        assertEquals(5 * 120, scan.pagesRead.size)
    }

    @Test
    fun anotherFileAtTheSameAddressIsReadFromItsFirstPage() = runBlocking {
        val import = queue.addPicked(listOf(file("Scan.pdf"))).single()
        reader(Scan(pages = 50, fingerprint = "old file", stopAt = 20)::read).readAll()
        assertEquals(20, row(import.id).pagesDone)

        // The file was replaced by another of the same name before the work started again.
        val replaced = Scan(pages = 8, fingerprint = "new file")
        reader(replaced::read).readAll()

        assertEquals((1..8).toList(), replaced.pagesRead)
        assertEquals("new file", row(import.id).fingerprint)
        assertEquals(ImportState.DONE, row(import.id).state)
    }

    @Test
    fun aPageThatStopsTheAppEveryTimeIsLeftOutAndTheRestIsRead() = runBlocking {
        val import = queue.addPicked(listOf(file("Scan.pdf"))).single()
        val pagesRead = ArrayList<Int>()
        var crashes = 0
        // Page 7 takes the whole process down: nothing is reported, the work is started again.
        val read: suspend (DocumentImport, ReadingLog) -> IndexResult = { doc, log ->
            val done = log.pagesDone("fp")
            log.reading("fp", done, done)
            log.pageCount(10)
            for (page in done + 1..10) {
                if (page == 7) { crashes++; throw CancellationException("process died") }
                pagesRead += page
                log.pageDone(page, page)
            }
            IndexResult.Success(doc.name, 9, 1L)
        }
        // The first start reads six pages; each one after it reads none.
        repeat(1 + DocumentImportReader.STARTS_WITHOUT_A_PAGE) { reader(read).readAll() }
        assertEquals("begun again and again at the same page", 6, row(import.id).pagesDone)
        assertEquals(ImportState.READING, row(import.id).state)

        reader(read).readAll()

        val done = row(import.id)
        assertEquals(ImportState.DONE, done.state)
        assertEquals("page 7 was given up on", 1, done.pagesSkipped)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 8, 9, 10), pagesRead)
        assertEquals(1 + DocumentImportReader.STARTS_WITHOUT_A_PAGE, crashes)
    }

    @Test
    fun aFileThatStopsTheAppBeforeItHasPagesFailsInsteadOfBeingBegunForEver() = runBlocking {
        val import = queue.addPicked(listOf(file("Book.epub", "application/epub+zip"))).single()
        var starts = 0
        val read: suspend (DocumentImport, ReadingLog) -> IndexResult = { _, log ->
            starts++
            log.reading(null, 0, 0)
            throw CancellationException("process died")
        }
        repeat(DocumentImportReader.STARTS_WITHOUT_A_PAGE + 3) { reader(read).readAll() }
        assertEquals(DocumentImportReader.STARTS_WITHOUT_A_PAGE, starts)
        assertEquals(ImportState.FAILED, row(import.id).state)
        assertEquals(ImportFailure.OTHER, row(import.id).failure)
    }

    @Test
    fun aFileThatFailedIsReadAgainWhenAsked() = runBlocking {
        val import = queue.addPicked(listOf(file("Big.pdf"))).single()
        var memory = false
        val read: suspend (DocumentImport, ReadingLog) -> IndexResult = { doc, log ->
            log.reading("fp", 0, 0)
            if (memory) IndexResult.Success(doc.name, 4, 1L)
            else IndexResult.Failure(doc.name, IndexError.ExtractionFailed(OutOfMemoryError()))
        }
        reader(read).readAll()
        assertEquals(ImportFailure.TOO_BIG, row(import.id).failure)

        memory = true
        started = 0
        queue.retry(import.id)
        assertEquals(ImportState.WAITING, row(import.id).state)
        assertEquals(1, started)
        reader(read).readAll()
        assertEquals(ImportState.DONE, row(import.id).state)
        assertNull(row(import.id).failure)
    }

    @Test
    fun twoReadersNeverReadTheSameFile() = runBlocking {
        queue.addPicked((1..12).map { file("Doc$it.pdf") })
        val read = ConcurrentLinkedQueue<String>()
        reader { import, _ ->
            read += import.name
            kotlinx.coroutines.delay(5)
            IndexResult.Success(import.name, 1, 1L)
        }.readAll(readers = 3)

        assertEquals(12, read.size)
        assertEquals("each once", 12, read.toSet().size)
    }

    @Test
    fun aFileCutShortIsReadBeforeOnesThatHaveNotBegun() = runBlocking {
        val (first, second) = queue.addPicked(listOf(file("First.pdf"), file("Second.pdf")))
        db.documentImportDao().markReading(second.id, "fp", pagesDone = 9, pieces = 9, now = clock++)

        assertEquals(listOf(second.id, first.id), db.documentImportDao().unfinished().map { it.id })
    }
}
