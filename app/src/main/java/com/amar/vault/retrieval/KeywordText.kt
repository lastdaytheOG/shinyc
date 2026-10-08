package com.amar.vault.retrieval

import com.amar.vault.ItemType
import com.amar.vault.VaultItem
import com.amar.vault.VaultItemSearchData

/**
 * What the keyword engine indexes for a row: its text, its tags, the name of its type and its
 * searchable name ([SearchableName]).
 *
 * One function, used when a row is indexed and when the engine is filled again at start-up.
 * They used to build the text in three different ways, so what a row could be found by
 * depended on whether the app had been restarted since it was added.
 */
object KeywordText {

    fun of(item: VaultItem): String =
        of(item.ocrText, item.tags, item.itemType, item.sourceFile, item.title)

    fun of(row: VaultItemSearchData): String =
        of(row.ocrText, row.tags, row.itemType, row.sourceFile, row.title)

    fun of(text: String, tags: String, type: ItemType, sourceFile: String?, title: String?): String =
        "$text ${besidesTheText(tags, type, SearchableName.of(type, sourceFile, title))}"

    /** What a row is indexed by besides its text: [of] is the text, a space, and this. */
    private fun besidesTheText(tags: String, type: ItemType, name: String): String =
        "$tags ${type.stored} $name"

    /**
     * For many rows one after another, as when the engine is filled at start-up: what each is
     * indexed by besides its text. The engine takes the two side by side
     * ([Bm25Index.addDocument]), which saves writing every row's text out again with a few
     * words on the end; and every piece of a document carries the document's file name, whose
     * searchable form is worked out here once for each name. Each of the two took longer than
     * the engine takes to index the row.
     */
    class ForManyRows {
        private val nameOfFile = HashMap<String, String>()

        fun besidesTheText(row: VaultItemSearchData): String {
            val name = if (row.title != null || !row.itemType.isDocument) {
                SearchableName.of(row.itemType, row.sourceFile, row.title)
            } else {
                nameOfFile.getOrPut(row.sourceFile) { SearchableName.of(row.itemType, row.sourceFile, null) }
            }
            return besidesTheText(row.tags, row.itemType, name)
        }
    }
}
