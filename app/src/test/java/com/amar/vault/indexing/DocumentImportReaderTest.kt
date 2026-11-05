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
import kotlinx.coroutines.launch
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
        PdfPasswords.clear()
    }

    @After
    fun close() {
        db.close()
        PdfPasswords.clear()
    }

    private fun file(name: String, type: String = "application/pdf") = FileToRead(Uri.parse("content://docs/$name"), type)

    private suspend fun row(id: String) = db.documentImportDao().getById(id)!!

    /**
     * A scan of [pages] pages, read the way the indexer reads one: it asks where to start, says
     * so, and reports each page as it finishes it. [stopAt] cuts the reading short after that
     * page, as the system does when background work has had its ten minutes. (Cut short from
     * inside like this, the reader's call returns; what matters is what is left written down.)
     */
    private class Scan(
        val pages: Int, val fingerprint: String = "fp", var stopAt: Int? = null,
        /** Called as a page is read: where a test moves its clock on. */
        val onPage: () -> Unit = {},
        /** Stops the reading as the next page begins, before it is done: the charger pulled mid-page. */
        var stopBeforeNextPageDone: Boolean = false,
    ) {
        val pagesRead = ArrayList<Int>()

        suspend fun read(import: DocumentImport, log: ReadingLog): IndexResult {
            val done = log.pagesDone(fingerprint)
            log.reading(fingerprint, done, pieces = done)
            log.pageCount(pages)
            for (page in done + 1..pages) {
                if (log.shouldPause()) return IndexResult.Paused(import.name)
                if (stopBeforeNextPageDone) throw CancellationException("the charger was pulled")
                pagesRead += page
                onPage()
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

    // ── A long scan on battery ──────────────────────────────────────────────────────────

    /** A reader whose time is [clock], which only a scan moves: 100 ms a reading at once, 40 ms a page. */
    private fun timedReader(read: suspend (DocumentImport, ReadingLog) -> IndexResult) =
        DocumentImportReader(db, read, now = { clock }, readAtOnceMs = 100)

    private fun longScan(pages: Int = 10) = Scan(pages, onPage = { clock += 40 })

    @Test
    fun aLongScanStopsAtItsTimeWithWhatItReadAndWaitsForTheCharger() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val scan = longScan()

        val ended = timedReader(scan::read).readAll()

        val row = row(import.id)
        assertEquals("three pages take 120 ms", 3, row.pagesDone)
        assertEquals(10, row.pageCount)
        assertTrue(row.waitsForCharger)
        assertEquals("told to whoever says how the run ended", listOf(import.id), ended.map { it.id })
        assertFalse("not finished: it stays on the list", row.isFinished)
        assertEquals(1, db.documentImportDao().countWaitingForCharger())
    }

    @Test
    fun aFileBehindALongScanIsNotKeptWaiting() = runBlocking {
        val (long, short) = queue.addPicked(listOf(file("Register.pdf"), file("Receipt.pdf")))
        val order = ArrayList<String>()
        val scan = longScan()

        timedReader { import, log ->
            order += import.name
            if (import.id == long.id) scan.read(import, log) else IndexResult.Success(import.name, 1, 1L)
        }.readAll(readers = 1)

        assertEquals(listOf("Register.pdf", "Receipt.pdf"), order)
        assertEquals(ImportState.DONE, row(short.id).state)
        assertTrue(row(long.id).waitsForCharger)
    }

    @Test
    fun nothingIsReadAgainAtOnceThatWaitsForTheCharger() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val scan = longScan()
        timedReader(scan::read).readAll()
        scan.pagesRead.clear()

        val second = timedReader(scan::read).readAll()

        assertTrue("it would stop and be taken up again for ever", second.isEmpty())
        assertTrue(scan.pagesRead.isEmpty())
        assertTrue(row(import.id).waitsForCharger)
    }

    @Test
    fun onTheChargerTheRestIsReadFromThePageItReached() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val scan = longScan()
        timedReader(scan::read).readAll()

        timedReader(scan::read).readAll(lane = ImportLane.ON_CHARGE)

        assertEquals("every page once, in order", (1..10).toList(), scan.pagesRead)
        assertEquals(ImportState.DONE, row(import.id).state)
        assertEquals(10, row(import.id).pagesDone)
        assertEquals(0, db.documentImportDao().countWaitingForCharger())
    }

    @Test
    fun theChargerLaneTakesOnlyWhatWaitsForIt() = runBlocking {
        val import = queue.addPicked(listOf(file("JustAdded.pdf"))).single()
        var reads = 0

        timedReader { i, _ -> reads++; IndexResult.Success(i.name, 1, 1L) }.readAll(lane = ImportLane.ON_CHARGE)

        assertEquals(0, reads)
        assertEquals(ImportState.WAITING, row(import.id).state)
    }

    @Test
    fun aChargerPulledMidPageLosesNothingAndIsNotCountedAgainstThePage() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val scan = longScan()
        timedReader(scan::read).readAll()
        val reached = row(import.id).pagesDone

        // More often than the times a page may stop the app before it is given up on.
        scan.stopBeforeNextPageDone = true
        repeat(DocumentImportReader.STARTS_WITHOUT_A_PAGE + 2) {
            timedReader(scan::read).readAll(lane = ImportLane.ON_CHARGE)
            assertTrue("back to waiting for the charger", row(import.id).waitsForCharger)
        }
        assertEquals(reached, row(import.id).pagesDone)
        assertEquals("no page was given up on", 0, row(import.id).pagesSkipped)

        scan.stopBeforeNextPageDone = false
        timedReader(scan::read).readAll(lane = ImportLane.ON_CHARGE)
        assertEquals((1..10).toList(), scan.pagesRead)
        assertEquals(ImportState.DONE, row(import.id).state)
    }

    @Test
    fun askedForTheRestNowItIsReadOnBatteryWithoutStopping() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val scan = longScan()
        timedReader(scan::read).readAll()
        val startedBefore = started

        queue.readRestNow(import.id)

        assertEquals("the reading is set going", startedBefore + 1, started)
        assertTrue(row(import.id).readsRestNow)
        timedReader(scan::read).readAll()
        assertEquals((1..10).toList(), scan.pagesRead)
        assertEquals(ImportState.DONE, row(import.id).state)
    }

    @Test
    fun askedForTheRestNowAndStoppedByTheSystemItIsStillAskedFor() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val scan = longScan()
        timedReader(scan::read).readAll()
        queue.readRestNow(import.id)

        scan.stopBeforeNextPageDone = true
        timedReader(scan::read).readAll()

        assertTrue(row(import.id).readsRestNow)
    }

    @Test
    fun readingStopsOnlyAfterAPageIsFinishedSoEveryStopMovesTheFileOn() = runBlocking {
        // A reading with no time at all still reads a page; otherwise it would stop where it began.
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val scan = Scan(pages = 5)

        DocumentImportReader(db, scan::read, now = { clock }, readAtOnceMs = 0).readAll()

        assertEquals(1, row(import.id).pagesDone)
        assertTrue(row(import.id).waitsForCharger)
    }

    @Test
    fun aFileThatFailedAfterSomePagesDoesNotWaitForTheChargerWhenTriedAgain() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        db.documentImportDao().markReading(import.id, "fp", pagesDone = 4, pieces = 4, now = clock)
        db.documentImportDao().finish(import.id, ImportState.FAILED, 4, ImportFailure.TOO_BIG, null, clock)

        queue.retry(import.id)

        assertFalse(row(import.id).waitsForCharger)
        assertTrue(ImportLane.AT_ONCE.takes(row(import.id)))
    }

    @Test
    fun twoReadersOfTwoLanesNeverReadTheSameFile() = runBlocking {
        // The lanes are two workers, so two readers; the indexer would call the second a duplicate.
        queue.addPicked((1..12).map { file("Doc$it.pdf") })
        val read = ConcurrentLinkedQueue<String>()
        val slowly: suspend (DocumentImport, ReadingLog) -> IndexResult = { import, _ ->
            read += import.name
            kotlinx.coroutines.delay(5)
            IndexResult.Success(import.name, 1, 1L)
        }
        kotlinx.coroutines.coroutineScope {
            repeat(2) { launch { reader(slowly).readAll(readers = 2) } }
        }

        assertEquals("each once", 12, read.size)
        assertEquals(12, read.toSet().size)
    }

    @Test
    fun aSharedFileThatWasReadIsNotReadAgainWhenTheRepairAsksAboutIt() = runBlocking {
        val item = VaultItem(id = "saved-1", uri = "/data/shared/saved-1.pdf", ocrText = "", lang = "en", itemType = ItemType.PDF, sourceFile = "Bill.pdf", timestamp = 0L)
        assertTrue(queue.addShared(item))
        reader { import, _ -> IndexResult.Success(import.name, 3, 1L) }.readAll()
        assertEquals(ImportState.DONE, row("saved-1").state)
        val startedBefore = started

        assertTrue("it is a document, and it is taken care of", queue.addShared(item))

        assertEquals("still says it was read", ImportState.DONE, row("saved-1").state)
        assertEquals("nothing is set going", startedBefore, started)
    }

    @Test
    fun aPausedScanIsNotBegunAgainWhenTheRepairAsksAboutIt() = runBlocking {
        val item = VaultItem(id = "saved-2", uri = "/data/shared/saved-2.pdf", ocrText = "", lang = "en", itemType = ItemType.PDF, sourceFile = "Register.pdf", timestamp = 0L)
        assertTrue(queue.addShared(item))
        timedReader(longScan()::read).readAll()
        val before = row("saved-2")
        assertTrue(before.waitsForCharger)

        assertTrue(queue.addShared(item))

        assertEquals("what it read and why it waits are untouched", before, row("saved-2"))
    }

    // ── Locked PDFs ─────────────────────────────────────────────────────────────────────

    /** Files that open only with the password given for their name, as the indexer behaves. */
    private fun lockedWith(vararg passwords: Pair<String, String>): suspend (DocumentImport, ReadingLog) -> IndexResult {
        val needed = passwords.toMap()
        return { import, log ->
            log.reading("fp-${import.name}", 0, 0)
            val given = kotlin.coroutines.coroutineContext[PdfPassword]?.value
            val wanted = needed[import.name]
            if (wanted != null && given != wanted) {
                IndexResult.Failure(import.name, IndexError.ExtractionFailed(java.io.IOException("Cannot decrypt PDF, the password is incorrect")))
            } else IndexResult.Success(import.name, 1, 1L)
        }
    }

    @Test
    fun aLockedFileAsksForItsPasswordAndIsToldOfIt() = runBlocking {
        val import = queue.addPicked(listOf(file("Statement.pdf"))).single()
        val ended = reader(lockedWith("Statement.pdf" to "open-sesame")).readAll()

        assertEquals(ImportFailure.LOCKED, row(import.id).failure)
        assertEquals("This PDF is locked with a password.", ImportWords.progress(row(import.id)))
        assertEquals("nobody asked yet: the notification says so", listOf(import.id), ended.map { it.id })
    }

    @Test
    fun aWrongPasswordSaysSoAndIsNotKept() = runBlocking {
        val import = queue.addPicked(listOf(file("Statement.pdf"))).single()
        val read = lockedWith("Statement.pdf" to "open-sesame")
        reader(read).readAll()
        val startedBefore = started

        queue.unlock(import.id, "wrong")

        assertEquals("the reading is set going", startedBefore + 1, started)
        val ended = reader(read).readAll()
        assertEquals("That password did not open this PDF.", ImportWords.progress(row(import.id)))
        assertTrue("the user is at the card: no notification too", ended.isEmpty())
        assertNull(PdfPasswords.of(import.id))
    }

    @Test
    fun theRightPasswordReadsItAndIsForgottenOnceRead() = runBlocking {
        val import = queue.addPicked(listOf(file("Statement.pdf"))).single()
        val read = lockedWith("Statement.pdf" to "open-sesame")
        reader(read).readAll()

        queue.unlock(import.id, "open-sesame")
        reader(read).readAll()

        assertEquals(ImportState.DONE, row(import.id).state)
        assertNull("not held for reading any more", PdfPasswords.of(import.id))
        assertEquals("the viewer need not ask while the app runs", "open-sesame", PdfPasswords.forViewing("fp-Statement.pdf"))
        assertFalse("no column of the row holds it", row(import.id).toString().contains("open-sesame"))
    }

    @Test
    fun aPasswordThatOpensOneFileIsTriedOnTheOtherLockedOnes() = runBlocking {
        val (march, april, other) = queue.addPicked(listOf(file("March.pdf"), file("April.pdf"), file("Other.pdf")))
        val read = lockedWith("March.pdf" to "same", "April.pdf" to "same", "Other.pdf" to "different")
        reader(read).readAll()

        queue.unlock(march.id, "same")
        val ended = reader(read).readAll()

        assertEquals(ImportState.DONE, row(march.id).state)
        assertEquals("opened by the same password, typed once", ImportState.DONE, row(april.id).state)
        assertEquals(ImportFailure.LOCKED, row(other.id).failure)
        assertEquals(
            "nothing was typed for this one, so nothing typed was wrong",
            "This PDF is locked with a password.", ImportWords.progress(row(other.id)),
        )
        assertEquals("only what was read is told of", setOf(march.id, april.id), ended.map { it.id }.toSet())
        assertNull(PdfPasswords.of(other.id))
    }

    @Test
    fun aWrongPasswordIsNotTriedOnOtherFiles() = runBlocking {
        val (one, two) = queue.addPicked(listOf(file("One.pdf"), file("Two.pdf")))
        val read = lockedWith("One.pdf" to "a", "Two.pdf" to "b")
        reader(read).readAll()

        queue.unlock(one.id, "b")
        reader(read).readAll()

        assertEquals(ImportFailure.LOCKED, row(one.id).failure)
        assertEquals("only a password that opened something goes further", ImportFailure.LOCKED, row(two.id).failure)
    }

    @Test
    fun aPausedLockedScanKeepsItsPasswordForTheRest() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        val seen = ArrayList<String?>()
        val scan = longScan()
        val read: suspend (DocumentImport, ReadingLog) -> IndexResult = { i, log ->
            seen += kotlin.coroutines.coroutineContext[PdfPassword]?.value
            scan.read(i, log)
        }
        db.documentImportDao().finish(import.id, ImportState.FAILED, 0, ImportFailure.LOCKED, null, clock)
        queue.unlock(import.id, "open-sesame")

        timedReader(read).readAll()
        assertTrue(row(import.id).waitsForCharger)
        timedReader(read).readAll(lane = ImportLane.ON_CHARGE)

        assertEquals(ImportState.DONE, row(import.id).state)
        assertEquals(listOf<String?>("open-sesame", "open-sesame"), seen)
        assertNull(PdfPasswords.of(import.id))
    }

    @Test
    fun aPasswordIsNotKeptForAFileThatIsNotWaitingForOne() = runBlocking {
        val import = queue.addPicked(listOf(file("Plain.pdf"))).single()

        queue.unlock(import.id, "open-sesame")

        assertNull(PdfPasswords.of(import.id))
        assertEquals(ImportState.WAITING, row(import.id).state)
    }

    @Test
    fun aPasswordDoesNotShowWhenItIsPrinted() {
        assertFalse(PdfPassword("open-sesame").toString().contains("open-sesame"))
    }

    @Test
    fun whatIsSaidOfAScanThatWaitsForTheCharger() = runBlocking {
        val import = queue.addPicked(listOf(file("Register.pdf"))).single()
        timedReader(longScan()::read).readAll()
        val row = row(import.id)

        assertEquals(
            "Read 3 of 10 pages. You can search those now. The rest is read while the phone charges.",
            ImportWords.progress(row),
        )
        assertEquals("Register.pdf: 3 of 10 pages read", ImportNotices.wordsAboutThePause(row).first)
    }
}
