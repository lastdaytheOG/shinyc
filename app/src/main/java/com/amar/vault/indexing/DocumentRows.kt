package com.amar.vault.indexing

import com.amar.vault.DocumentIndexer
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem

/**
 * The rows a document's pieces are stored as. Nothing here touches a device, so what a piece
 * is stored with — above all its tags, which are worked out a page at a time ([TagUnits]) —
 * can be checked on its own.
 */
internal object DocumentRows {

    /**
     * [pieces] of [document] as rows, in the order given. [totalChunks] is the size of the
     * document when it is known, and 0 while it is still being read page by page.
     */
    fun of(document: VaultDocument, pieces: List<PagedChunk>, totalChunks: Int): List<VaultItem> =
        TagUnits.of(pieces) { it.pdfPage }.flatMap { together ->
            val tags = AutoTags.of(TagUnits.text(together.map { it.text }), document.itemType)
            together.map { piece ->
                VaultItem(
                    id          = "${document.id}_chunk${piece.chunkIndex}",
                    uri         = document.uri,
                    ocrText     = piece.text,
                    lang        = DocumentIndexer.LanguageDetector.detect(piece.text),
                    itemType    = document.itemType,
                    pageNum     = piece.pdfPage ?: piece.chunkIndex,
                    sourceFile  = document.name,
                    timestamp   = System.currentTimeMillis(),
                    pHash       = 0L,
                    tags        = tags,
                    contentHash = document.contentHash,
                    // Explicit ownership (Task 2) — the id string is a key, not a schema.
                    parentDocumentId = document.id,
                    chunkIndex       = piece.chunkIndex,
                    totalChunks      = totalChunks,
                )
            }
        }
}
