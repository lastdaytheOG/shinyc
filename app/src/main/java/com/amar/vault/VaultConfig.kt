package com.amar.vault

/**
 * Centralized architecture / tuning constants for the indexing + retrieval core.
 *
 * Purpose: eliminate the drift risk of the same value being declared independently
 * in multiple files. The most important cases are the ones that MUST stay in lockstep:
 *
 *   - [Embedding.DIM] is the single source of truth for the embedding dimension.
 *     Both the ONNX embedder and the HNSW vector store reference it, so they can
 *     never silently disagree (a mismatch would make every indexVector() fail the
 *     dimension check and drop vectors on the floor).
 *
 *   - [Chunking] is shared by the image and document indexers so their chunk sizing
 *     is defined once.
 *
 * This object holds compile-time constants only. Values are copied verbatim from the
 * previous inline declarations — behaviour is unchanged. This is deliberately NOT a
 * runtime config surface; it exists to remove duplication, not to add flexibility.
 */
object VaultConfig {

    object Embedding {
        /** BGE-M3 output dimension. Single source of truth for embedder + vector store. */
        const val DIM = 1024
        const val MAX_SEQ_LEN = 128

        // ── Embedding identity (Sprint 3B, Task 1) ──────────────────────────
        // Immutable identity of the embedding space every stored vector belongs to.
        // The vector store records these in embedding_manifest.json and in the
        // mappings-file header; a future model swap bumps MODEL_ID/MODEL_VERSION and
        // the mismatch against the persisted manifest is what triggers a background
        // re-embed migration. Never reuse a MODEL_ID+MODEL_VERSION pair across
        // incompatible vector spaces.
        /** Stable identifier of the embedding model family/artifact. */
        const val MODEL_ID = "bge-m3-ocr-int4"
        /** Version of that model artifact (quantization, fine-tune, export). */
        const val MODEL_VERSION = "1"
        /** Version of the on-device embedding storage schema (dim, normalization, pooling). */
        const val SCHEMA_VERSION = 1
    }

    object Vector {
        /** HNSW capacity ceiling. */
        const val MAX_ELEMENTS = 100_000
        const val DEFAULT_K = 20
        const val MAPPINGS_FILENAME = "vector_mappings.json"
        /** Sidecar manifest recording which embedding model produced the vector files. */
        const val EMBEDDING_MANIFEST_FILENAME = "embedding_manifest.json"
    }

    object Chunking {
        // Image OCR path (sliding word window)
        const val CHUNK_SIZE = 200
        const val CHUNK_OVERLAP = 30
        // Document path (sentence-aware)
        const val DOC_TARGET_WORDS = 200
        const val DOC_OVERLAP_SENTENCES = 3
    }

    object Ocr {
        const val ML_KIT_MIN_LENGTH = 10
    }

    object Indexing {
        /** BulkScanWorker burst size + cooldown. */
        const val BATCH_SIZE = 8
        const val COOL_DOWN_MS = 2000L
    }

    /**
     * Retrieval tuning — centralizes the previously-scattered candidate caps and RRF
     * constants. Every value here is FROZEN at the pre-Phase-1 effective value so the
     * extraction is result-identical; do not change these without a Phase 0B re-bless.
     */
    object Retrieval {
        // ── Candidate budgets (were: native≤50, vector k=50, take(50), take(30)) ──
        const val BM25_BUDGET = 50
        const val VECTOR_BUDGET = 50
        const val SUBSTRING_BUDGET = 50
        const val FUZZY_BUDGET = 30
        const val FTS_BUDGET = 50

        // ── RRF fusion (frozen) ──
        const val RRF_K = 60.0
        const val WEIGHT_BM25 = 1.2
        const val WEIGHT_VECTOR = 1.0
        const val WEIGHT_SUBSTRING = 1.1
        const val WEIGHT_FUZZY = 0.6

        // ── Fusion post-processing (frozen) ──
        const val LATE_SEMANTIC_WEIGHT = 0.5
        const val LATE_EMBED_SIM_THRESHOLD = 0.3
        const val LATE_EMBED_DOC_LIMIT = 10
        const val EXACT_PHRASE_BONUS = 0.10
        const val WORD_RATIO_BONUS = 0.06
        const val FUSION_TOPN = 50
        const val RERANK_TOPN = 100

        /**
         * Behaviour-sensitive O(N) elimination. When false, substring/fuzzy scan the full
         * corpus exactly as before (result-identical). When true, they become
         * candidate-bounded rescorers (FTS-backed) with a conditional full-scan fallback.
         * MUST remain false until the Phase 0B substring/typo/OCR suites validate flipping
         * it on against a real baseline — the runners are currently mocked, so this cannot
         * be validated yet.
         */
        const val CANDIDATE_BOUNDED = false
    }
}
