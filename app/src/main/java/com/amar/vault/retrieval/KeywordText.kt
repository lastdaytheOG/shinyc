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
        "$text $tags ${type.stored} ${SearchableName.of(type, sourceFile, title)}"
}
