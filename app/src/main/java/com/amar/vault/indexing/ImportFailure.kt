package com.amar.vault.indexing

import com.amar.vault.IndexError

/**
 * Why a file ended up without its text, as one of a few causes the user can do something
 * about. Each has its own words ([ImportWords]); "Extraction failed" told them nothing.
 */
enum class ImportFailure {
    /** The file is not where it was: moved, renamed, deleted, or its permission was taken back. */
    FILE_GONE,
    /** A PDF that asks for a password before it opens. */
    LOCKED,
    /** Not one of the kinds of file the app reads. */
    NOT_A_DOCUMENT,
    /** The file does not hold what its kind should: cut short, or damaged. */
    DAMAGED,
    /** It opened, and no words were found in it. It is stored under its name. */
    NO_TEXT,
    /** Reading it needed more memory than the phone had free. */
    TOO_BIG,
    /** The phone's storage is full. */
    NO_SPACE,
    /** Anything else; [DocumentImport.failureDetail] keeps the error's own text. */
    OTHER;

    companion object {

        /** The cause behind what the indexer reported, with the detail its words need. */
        fun of(error: IndexError, fileName: String): Pair<ImportFailure, String?> = when (error) {
            is IndexError.UnsupportedFormat ->
                NOT_A_DOCUMENT to fileName.substringAfterLast('.', "").trim().lowercase().take(8).ifBlank { null }
            IndexError.EmptyContent -> NO_TEXT to null
            is IndexError.ExtractionFailed -> ofReading(error.cause)
            is IndexError.StorageFailed -> ofStoring(error.cause)
        }

        /** [error] and everything it was caused by, outermost first. */
        private fun chain(error: Throwable): List<Throwable> =
            generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(8).toList()

        private fun ofReading(error: Throwable): Pair<ImportFailure, String?> {
            val chain = chain(error)
            val names = chain.map { it::class.java.simpleName.orEmpty() }
            val said = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return when {
                chain.any { it is OutOfMemoryError } -> TOO_BIG to null
                names.any { it == "InvalidPasswordException" } || "password" in said -> LOCKED to null
                chain.any { it is java.io.FileNotFoundException || it is SecurityException } ||
                    "cannot open" in said -> FILE_GONE to null
                isNoSpace(chain, said) -> NO_SPACE to null
                // What PDFBox, the zip reader and the Office reader say of a file that is not whole.
                chain.any { it is java.util.zip.ZipException || it is java.io.EOFException } ||
                    names.any { it in DAMAGED_ERRORS } ||
                    DAMAGED_WORDS.any { it in said } -> DAMAGED to null
                else -> OTHER to detail(error)
            }
        }

        private fun ofStoring(error: Throwable): Pair<ImportFailure, String?> {
            val chain = chain(error)
            val said = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
            return when {
                chain.any { it is OutOfMemoryError } -> TOO_BIG to null
                isNoSpace(chain, said) -> NO_SPACE to null
                chain.any { it is java.io.FileNotFoundException || it is SecurityException } -> FILE_GONE to null
                else -> OTHER to detail(error)
            }
        }

        private fun isNoSpace(chain: List<Throwable>, said: String): Boolean =
            chain.any { it::class.java.simpleName in setOf("SQLiteFullException", "SQLiteDiskIOException") } ||
                "enospc" in said || "no space left" in said || "database or disk is full" in said

        private fun detail(error: Throwable): String =
            (error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty().ifBlank { error::class.java.simpleName })
                .take(120)

        private val DAMAGED_ERRORS = setOf(
            "NotOfficeXmlFileException", "OLE2NotOfficeXmlFileException", "InvalidFormatException",
            "NotOLE2FileException", "EmptyFileException", "POIXMLException",
        )
        private val DAMAGED_WORDS = listOf(
            "end-of-file", "end of file", "header doesn't contain versioninfo", "missing root object",
            "expected='", "xref", "not a pdf", "zip file", "package should contain", "premature", "has no pages",
        )
    }
}

/**
 * What the user is told about a file: what happened to it, and what they can do. Written for
 * someone who has never seen the inside of the app; no word here names a part of it.
 */
object ImportWords {

    /** What happened, in a sentence. */
    fun whatHappened(failure: ImportFailure, detail: String?): String = when (failure) {
        ImportFailure.FILE_GONE ->
            "The file could not be opened. It was moved, renamed or deleted after you chose it."
        ImportFailure.LOCKED ->
            "This PDF is locked with a password, so its text cannot be read."
        ImportFailure.NOT_A_DOCUMENT ->
            (if (detail.isNullOrBlank()) "This kind of file cannot be read." else "A .$detail file cannot be read.") +
                " The app reads PDF, Word (.docx), Excel (.xlsx) and EPUB."
        ImportFailure.DAMAGED ->
            "The file is damaged or incomplete, so it could not be read."
        ImportFailure.NO_TEXT ->
            "No words were found in it. It is in your vault under its name only."
        ImportFailure.TOO_BIG ->
            "The file is too big for the memory this phone has free."
        ImportFailure.NO_SPACE ->
            "The phone's storage is full, so its text could not be saved."
        ImportFailure.OTHER ->
            "It could not be read" + (if (detail.isNullOrBlank()) "." else ": $detail")
    }

    /** What to do about it. */
    fun whatToDo(failure: ImportFailure, origin: ImportOrigin): String = when (failure) {
        ImportFailure.FILE_GONE ->
            if (origin == ImportOrigin.SHARED) "Share it to the app again."
            else "Choose it again from where it is now."
        ImportFailure.LOCKED ->
            "Open it in another app with its password, print it to a new PDF, and add that one."
        ImportFailure.NOT_A_DOCUMENT ->
            "Save it as a PDF and add the PDF."
        ImportFailure.DAMAGED ->
            "See if it opens in another app. If it was downloaded, download it again and add the new copy."
        ImportFailure.NO_TEXT ->
            "If it is a scan, a sharper or straighter scan may be readable."
        ImportFailure.TOO_BIG ->
            "Close other apps and tap Try again, or split the file into smaller parts."
        ImportFailure.NO_SPACE ->
            "Free some space, then tap Try again."
        ImportFailure.OTHER ->
            "Tap Try again. If it fails the same way, the file may be one the app cannot read."
    }

    /** True where reading the same file again can end differently. */
    fun canTryAgain(failure: ImportFailure): Boolean = when (failure) {
        ImportFailure.TOO_BIG, ImportFailure.NO_SPACE, ImportFailure.OTHER, ImportFailure.FILE_GONE -> true
        ImportFailure.LOCKED, ImportFailure.NOT_A_DOCUMENT, ImportFailure.DAMAGED, ImportFailure.NO_TEXT -> false
    }

    /** One line on where a reading stands, for the list and the notification. */
    fun progress(import: DocumentImport): String = when (import.state) {
        ImportState.WAITING -> "Waiting its turn"
        ImportState.READING -> {
            val pages = import.pageCount
            when {
                pages != null && import.pagesDone > 0 -> "Reading: page ${import.pagesDone} of $pages. What is read can be searched already."
                pages != null -> "Reading: $pages ${if (pages == 1) "page" else "pages"}"
                else -> "Reading"
            }
        }
        ImportState.DONE -> {
            val pages = import.pageCount
            if (pages != null) "Read: $pages ${if (pages == 1) "page" else "pages"}" else "Read"
        }
        ImportState.ALREADY_THERE -> "Already in your vault"
        ImportState.FAILED -> whatHappened(import.failure ?: ImportFailure.OTHER, import.failureDetail)
    }
}
