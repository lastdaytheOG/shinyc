package com.amar.vault.retrieval

import com.amar.vault.VaultItem

/**
 * The name a person would search a stored item by: a document's file name and any item's
 * title. Neither is part of `ocrText`, so before this a PDF could only be found by words
 * printed inside it — never by what it is called.
 *
 * A file name is used for documents only. An image's `sourceFile` is the last segment of its
 * uri (a media-store number or a camera-generated name), which nobody searches for and which
 * would match queries such as "screenshot" against every screenshot.
 *
 * The result holds the name as written, then again with every run of separators turned into
 * a space ("Aadhaar_Card-2024.pdf Aadhaar Card 2024 pdf"), so a lane that splits on
 * whitespace sees the words and one that matches substrings still sees the original spelling.
 * One function feeds BM25 (index time and the startup rebuild) and the scan lanes, so the two
 * cannot drift apart.
 */
object SearchableName {

    val DOCUMENT_TYPES = setOf("pdf", "word", "excel", "epub")

    /** Anything that is not a letter, a combining mark (Devanagari vowel signs) or a digit. */
    private val SEPARATORS = Regex("[^\\p{L}\\p{M}\\p{N}]+")

    fun of(item: VaultItem): String = of(item.itemType, item.sourceFile, item.title)

    fun of(itemType: String, sourceFile: String?, title: String?): String {
        val file = sourceFile?.trim()?.takeIf { it.isNotEmpty() && itemType in DOCUMENT_TYPES }
        val heading = title?.trim()?.takeIf { it.isNotEmpty() && it != file }
        if (file == null && heading == null) return ""
        val written = listOfNotNull(file, heading).joinToString(" ")
        val words = SEPARATORS.replace(written, " ").trim()
        return if (words == written) written else "$written $words"
    }
}
