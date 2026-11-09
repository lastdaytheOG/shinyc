package com.amar.vault

import android.content.Context
import com.amar.vault.indexing.DocumentRelink
import com.amar.vault.share.open.ShareContentUri

/** What Home says about the vault, in words. */
internal object HomeWords {
    fun summary(documents: Int, pictures: Int): String {
        fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
        return when {
            documents == 0 && pictures == 0 -> "Nothing in your vault yet"
            pictures == 0 -> count(documents, "document", "documents") + " you can search"
            documents == 0 -> count(pictures, "picture", "pictures") + " you can search"
            else -> count(documents, "document", "documents") + " and " + count(pictures, "picture", "pictures") + " you can search"
        }
    }

    const val EMPTY_HINT = "Add a PDF, Word, Excel or EPUB file and every page of it can be searched. " +
        "Screenshots and photos are read on their own."

    fun added(addedAt: Long, pages: Int?, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - addedAt) / 60_000
        val whenAdded = when {
            minutes < 1 -> "Added just now"
            minutes < 60 -> "Added $minutes min ago"
            minutes < 24 * 60 -> "Added ${minutes / 60} hr ago"
            minutes < 2 * 24 * 60 -> "Added yesterday"
            else -> "Added ${minutes / (24 * 60)} days ago"
        }
        return if (pages == null) whenAdded else "$whenAdded · " + (if (pages == 1) "1 page" else "$pages pages")
    }
}

/** What is said about missing or moved files. */
internal object MissingWords {
    fun headline(count: Int): String =
        if (count == 1) "1 document cannot be opened" else "$count documents cannot be opened"

    const val EXPLANATION =
        "The file was moved, renamed or deleted after you added it. Its text can still be searched. " +
            "Show the app where the file is now and it opens again."

    fun afterLooking(found: Int, lookedFor: Int, picked: Int): String = when {
        found == 0 && picked == 1 -> "That is not one of the missing files: its contents are different."
        found == 0 -> "None of those is one of the missing files: their contents are different."
        found == lookedFor -> if (found == 1) "Found it. It opens again." else "Found all $found. They open again."
        else -> "Found $found of $lookedFor."
    }
}

/** Grounded answer source navigation and excerpt formatting. */
internal object AnswerSources {
    fun wordsOn(source: VaultItem, question: String): String? =
        MatchExcerpt.of(source.ocrText, DirectAnswers.aboutWords(question))

    fun open(context: Context, source: VaultItem, question: String): Boolean {
        if (source.itemType.isImage) {
            AmarImageViewerActivity.open(context, source)
            return true
        }
        if (source.itemType != ItemType.PDF || source.uri.startsWith("http", ignoreCase = true)) return false
        val address = DocumentRelink.currentAddress(source.uri)
        val uri = ShareContentUri.resolve(context, address) ?: return false
        val page = source.pdfPage
        PdfViewerActivity.open(
            context = context,
            uri = uri,
            page = page ?: 0,
            searchQuery = if (page != null) DirectAnswers.aboutWords(question).joinToString(" ") else "",
            fileName = source.title ?: source.sourceFile,
            matchText = wordsOn(source, question).orEmpty()
        )
        return true
    }
}
