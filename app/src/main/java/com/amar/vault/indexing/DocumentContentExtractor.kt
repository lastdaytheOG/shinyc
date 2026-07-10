package com.amar.vault.indexing

import android.content.Context
import android.net.Uri

/**
 * Document content-extraction stage (document/BM25 path).
 *
 * Owns everything between a `(uri, mimeType)` and the paged, chunked text ready for chunk-row
 * building: document-family resolution (registry lookup), stream loading, format-specific text
 * extraction, and page traversal. All parsing lives in the per-format [FormatExtractor]s reached
 * through [DocumentFormatRegistry]; this class is the thin dispatch + result-shaping seam.
 *
 * Concrete (no interface — single implementation, no polymorphism), per the standing rule. The
 * genuine extensibility point is the registry: adding a format = one new [FormatExtractor], with
 * neither this class nor [DocumentIndexer] touched.
 *
 * Behaviour is byte-identical to the pre-2B inline `DocumentIndexer.extractPagedChunks` family:
 * same registry contents (mime → tag/itemType), same chunker (sentence-aware), same per-format
 * algorithms moved verbatim.
 */
class DocumentContentExtractor(
    private val registry: DocumentFormatRegistry = DocumentFormatRegistry(),
    private val chunker: Chunker = SentenceAwareChunker(),
) {

    /** Supported mime types — backs [DocumentIndexer.SUPPORTED_TYPES]/the document picker. */
    val supportedMimeTypes: Set<String> get() = registry.supportedMimeTypes

    fun isSupported(mimeType: String): Boolean = registry.isSupported(mimeType)

    /** Extracted, paged-and-chunked document content plus the format's tag/itemType descriptors. */
    data class Content(
        val pagedChunks: List<PagedChunk>,
        /** Family tag prepended to generated tags (was DocFamily.tag). */
        val tag: String,
        /** VaultItem.itemType for chunks of this family (was DocFamily.itemType). */
        val itemType: String,
    )

    /**
     * Load, extract and chunk the document. Callers MUST [isSupported] first — an unsupported
     * mime type is a programming error here (the orchestrator maps it to UnsupportedFormat before
     * this is ever reached). Extraction/IO failures propagate as thrown exceptions, exactly as the
     * prior inline `extractPagedChunks` did (the orchestrator wraps the call in `runCatching`).
     */
    suspend fun extract(context: Context, uri: Uri, mimeType: String): Content {
        val extractor = registry.extractorFor(mimeType)
            ?: error("Unsupported mime type '$mimeType' — call isSupported() before extract()")
        return Content(
            pagedChunks = extractor.extract(context, uri, chunker),
            tag = extractor.tag,
            itemType = extractor.itemType,
        )
    }
}
