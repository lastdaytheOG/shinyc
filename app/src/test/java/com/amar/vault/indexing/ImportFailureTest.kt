package com.amar.vault.indexing

import com.amar.vault.IndexError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the user is told when a file could not be read: the cause is one they can act on, and
 * the words say what happened and what to do, with none of the app's own vocabulary in them.
 */
class ImportFailureTest {

    private fun reading(cause: Throwable) = ImportFailure.of(IndexError.ExtractionFailed(cause), "File.pdf").first
    private fun storing(cause: Throwable) = ImportFailure.of(IndexError.StorageFailed(cause), "File.pdf").first

    /** Stands in for PDFBox's own exception, which is told by its name. */
    private class InvalidPasswordException(message: String) : java.io.IOException(message)
    private class SQLiteFullException(message: String) : RuntimeException(message)

    @Test
    fun theCauseIsToldFromWhatWentWrong() {
        assertEquals(ImportFailure.LOCKED, reading(InvalidPasswordException("Cannot decrypt PDF, the password is incorrect")))
        assertEquals(ImportFailure.FILE_GONE, reading(java.io.FileNotFoundException("No such file or directory")))
        assertEquals(ImportFailure.FILE_GONE, reading(SecurityException("Permission Denial: opening provider")))
        assertEquals(ImportFailure.FILE_GONE, reading(IllegalStateException("Cannot open content://docs/1")))
        assertEquals(ImportFailure.TOO_BIG, reading(OutOfMemoryError("Failed to allocate a 268435472 byte allocation")))
        assertEquals(ImportFailure.DAMAGED, reading(java.io.IOException("Error: End-of-File, expected line")))
        assertEquals(ImportFailure.DAMAGED, reading(java.io.IOException("Error: Header doesn't contain versioninfo")))
        assertEquals(ImportFailure.DAMAGED, reading(java.util.zip.ZipException("zip END header not found")))
        // A PDF cut off part-way: PDFBox opens it and finds no page.
        assertEquals(ImportFailure.DAMAGED, reading(java.io.IOException(NO_PAGES)))
        assertEquals(ImportFailure.NO_SPACE, reading(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(ImportFailure.NO_SPACE, storing(SQLiteFullException("database or disk is full (code 13 SQLITE_FULL)")))
        assertEquals(ImportFailure.OTHER, reading(IllegalArgumentException("Unexpected glyph table")))
        assertEquals(ImportFailure.NO_TEXT, ImportFailure.of(IndexError.EmptyContent, "Scan.pdf").first)
    }

    @Test
    fun aCauseWrappedInAnotherErrorIsStillFound() {
        assertEquals(ImportFailure.LOCKED, reading(RuntimeException("extract failed", InvalidPasswordException("password"))))
        assertEquals(ImportFailure.FILE_GONE, reading(java.io.IOException("read", java.io.FileNotFoundException("gone"))))
    }

    @Test
    fun aFileOfAKindTheAppDoesNotReadIsNamedByItsEnding() {
        val (failure, detail) = ImportFailure.of(IndexError.UnsupportedFormat("application/vnd.ms-powerpoint"), "Slides Q3.PPTX")
        assertEquals(ImportFailure.NOT_A_DOCUMENT, failure)
        assertEquals("pptx", detail)
        assertEquals(
            "A .pptx file cannot be read. The app reads PDF, Word (.docx), Excel (.xlsx) and EPUB.",
            ImportWords.whatHappened(failure, detail),
        )
        // A file with no ending at all.
        assertEquals(
            "This kind of file cannot be read. The app reads PDF, Word (.docx), Excel (.xlsx) and EPUB.",
            ImportWords.whatHappened(failure, ImportFailure.of(IndexError.UnsupportedFormat(""), "README").second),
        )
    }

    @Test
    fun anUnknownErrorKeepsItsOwnWordsAndNoMore() {
        val (failure, detail) = ImportFailure.of(
            IndexError.ExtractionFailed(IllegalArgumentException("Unexpected glyph table" + "\n\tat com.tom_roush.fontbox.ttf.TTFParser")),
            "File.pdf",
        )
        assertEquals("It could not be read: Unexpected glyph table", ImportWords.whatHappened(failure, detail))
    }

    @Test
    fun everyCauseSaysWhatHappenedAndWhatToDoInPlainWords() {
        // Words of the app's insides that mean nothing to the person using it. "Extraction
        // failed" and "Storage error" were all the screen said before.
        val insideWords = listOf("extraction", "storage error", "chunk", "index", "exception", "uri", "ocr", "null", "worker")
        for (failure in ImportFailure.entries) {
            for (origin in ImportOrigin.entries) {
                val happened = ImportWords.whatHappened(failure, null)
                val toDo = ImportWords.whatToDo(failure, origin)
                assertTrue("$failure says what happened", happened.length > 20 && happened.trimEnd().endsWith("."))
                assertTrue("$failure says what to do", toDo.length > 15 && toDo.trimEnd().endsWith("."))
                for (word in insideWords) {
                    assertFalse("$failure: \"$word\" in: $happened $toDo", Regex("\\b$word\\b").containsMatchIn("$happened $toDo".lowercase()))
                }
            }
        }
    }

    @Test
    fun whatTheScreenSaidBeforeDidNotSayWhatToDo() {
        // The control: the four lines the import screen had, whatever the cause.
        val before = listOf("Extraction failed", "Storage error", "No text could be read from it", "Unsupported: application/zip")
        val actions = listOf("again", "add", "open", "choose", "free", "save", "share", "scan", "split")
        assertTrue(before.none { line -> actions.any { it in line.lowercase() } })
        for (failure in ImportFailure.entries) {
            val toDo = ImportWords.whatToDo(failure, ImportOrigin.PICKED).lowercase()
            assertTrue("$failure: $toDo", actions.any { it in toDo })
        }
    }

    @Test
    fun tryAgainIsOfferedOnlyWhereItCanEndDifferently() {
        assertTrue(ImportWords.canTryAgain(ImportFailure.NO_SPACE))
        assertTrue(ImportWords.canTryAgain(ImportFailure.TOO_BIG))
        assertFalse("the password does not go away", ImportWords.canTryAgain(ImportFailure.LOCKED))
        assertFalse(ImportWords.canTryAgain(ImportFailure.NOT_A_DOCUMENT))
    }

    @Test
    fun aSharedFileThatIsGoneIsSharedAgainAPickedOneChosenAgain() {
        assertEquals("Share it to the app again.", ImportWords.whatToDo(ImportFailure.FILE_GONE, ImportOrigin.SHARED))
        assertEquals("Choose it again from where it is now.", ImportWords.whatToDo(ImportFailure.FILE_GONE, ImportOrigin.PICKED))
    }

    @Test
    fun theListSaysHowFarAReadingIs() {
        val row = DocumentImport(id = "a", uri = "content://a", name = "Scan.pdf", mimeType = "application/pdf",
            origin = ImportOrigin.PICKED, state = ImportState.WAITING, addedAt = 1L)
        assertEquals("Waiting its turn", ImportWords.progress(row))
        assertEquals(
            "Reading: page 120 of 300. What is read can be searched already.",
            ImportWords.progress(row.copy(state = ImportState.READING, pagesDone = 120, pageCount = 300)),
        )
        assertEquals("Read: 300 pages", ImportWords.progress(row.copy(state = ImportState.DONE, pageCount = 300)))
        assertEquals("Read: 1 page", ImportWords.progress(row.copy(state = ImportState.DONE, pageCount = 1)))
        assertEquals("Read", ImportWords.progress(row.copy(state = ImportState.DONE)))
        assertEquals("Already in your vault", ImportWords.progress(row.copy(state = ImportState.ALREADY_THERE)))
        assertEquals(
            "This PDF is locked with a password, so its text cannot be read.",
            ImportWords.progress(row.copy(state = ImportState.FAILED, failure = ImportFailure.LOCKED)),
        )
    }

    @Test
    fun theNotificationNamesTheFileAndSaysWhatToDo() {
        val locked = DocumentImport(id = "a", uri = "file:///a", name = "Statement.pdf", mimeType = "application/pdf",
            origin = ImportOrigin.SHARED, state = ImportState.FAILED, failure = ImportFailure.LOCKED, addedAt = 1L)
        val (title, body) = ImportNotices.words(failed = listOf(locked), read = emptyList())
        assertEquals("Could not read Statement.pdf", title)
        assertEquals(
            "This PDF is locked with a password, so its text cannot be read. " +
                "Open it in another app with its password, print it to a new PDF, and add that one.",
            body,
        )

        val scan = locked.copy(id = "b", name = "Ledger.pdf", state = ImportState.DONE, failure = null, pageCount = 312)
        assertEquals(
            "Finished reading Ledger.pdf" to "312 pages. You can search it now.",
            ImportNotices.words(failed = emptyList(), read = listOf(scan)),
        )
    }
}
