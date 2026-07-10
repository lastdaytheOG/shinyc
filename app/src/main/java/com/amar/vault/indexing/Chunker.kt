package com.amar.vault.indexing

import com.amar.vault.VaultConfig

/**
 * Chunking stage — splits normalized text into indexable chunks.
 *
 * Genuine polymorphism (per the Phase 2A/2B interface rule): the image/OCR path and the
 * document path use deliberately different, non-interchangeable strategies. Selection is
 * static per modality (the orchestrator holds the right one); behaviour is byte-identical
 * to the pre-2B inline implementations.
 */
interface Chunker {
    fun chunk(text: String): List<String>
}

// Sprint P1 — compiled once; both chunkers split on the same pattern.
private val WHITESPACE = Regex("\\s+")

/**
 * Sliding word-window chunker for the image/OCR path.
 * Verbatim from IndexingPipeline.slidingWindowChunks (Phase 2B extraction, no behaviour change).
 */
class WordWindowChunker : Chunker {
    private val chunkSize = VaultConfig.Chunking.CHUNK_SIZE
    private val overlap = VaultConfig.Chunking.CHUNK_OVERLAP

    override fun chunk(text: String): List<String> {
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size <= chunkSize) return listOf(text)

        val chunks = mutableListOf<String>()
        val stepSize = chunkSize - overlap
        var start = 0
        while (start < words.size) {
            val end = minOf(start + chunkSize, words.size)
            chunks.add(words.subList(start, end).joinToString(" "))
            if (end == words.size) break
            start += stepSize
        }
        return chunks
    }
}

/**
 * Sentence-aware chunker for the document path.
 * Verbatim from DocumentIndexer.SentenceAwareChunker (Phase 2B extraction, no behaviour change).
 */
class SentenceAwareChunker : Chunker {
    private val targetWords = VaultConfig.Chunking.DOC_TARGET_WORDS
    private val overlapSentences = VaultConfig.Chunking.DOC_OVERLAP_SENTENCES
    private val sentenceBoundary = Regex("""(?<=[.!?])\s+(?=[A-Zऀ-ॿ])|\n{2,}""")

    override fun chunk(text: String): List<String> {
        val sentences = text.split(sentenceBoundary).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return listOf(text)
        val chunks = mutableListOf<String>(); var i = 0
        while (i < sentences.size) {
            val window = mutableListOf<String>(); var wc = 0
            while (i < sentences.size && wc < targetWords) {
                window.add(sentences[i]); wc += sentences[i].split(WHITESPACE).size; i++
            }
            chunks.add(window.joinToString(" "))
            i = (i - overlapSentences).coerceAtLeast(i - window.size + 1)
            if (chunks.size > 1 && i <= 0) break
        }
        return chunks.ifEmpty { listOf(text) }
    }
}
