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

        /**
         * Run QUERY embeddings at the query's true token length instead of padding to
         * [MAX_SEQ_LEN]. Passages (everything stored in the vector index) stay padded, so no
         * stored vector changes and no re-embed is needed.
         *
         * Measured 2026-10-04 on a desktop CPU with this exact model (8 EN/HI queries of
         * 4–11 tokens, 4 threads): 6–15× faster per query embedding. The model's dynamic
         * INT8 quantization makes the vector differ slightly from the padded one (cosine
         * 0.989–0.994); ranked against padded passages the top hit was unchanged for 8/8
         * queries and the full order for 6/8. Not yet measured on a phone or against a golden
         * query set — set to false to restore the padded behaviour exactly.
         */
        const val QUERY_TRUE_LENGTH = true

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

        /**
         * Keep BM25/vector candidates in the engine's rank order after Room hydration.
         * `SELECT … WHERE id IN (…)` returns rows in primary-key order, so before this the
         * RRF "rank" of each BM25/vector candidate was its alphabetical id position (for a
         * book: chunk0, chunk1, chunk10, … regardless of relevance). The candidate SET is
         * unchanged; only the order fed to fusion is. This DOES change final ranking, and it
         * has not been scored against a golden query set yet — set to false to restore the
         * previous order exactly.
         */
        const val RANK_ORDER_HYDRATION = true

        /**
         * Let a vector hit count on its own. The legacy vector lane looks its hits up by CHUNK
         * id, which is not a Room row for image items, so those hits are dropped; the
         * text-presence gate then removes any hit that shares no word with the query. Together
         * that means an item is never found by meaning alone. When true, hits resolve to the
         * item that owns them and those at or above [SEMANTIC_MIN_SIMILARITY] pass the gate.
         *
         * OFF, deliberately. Measured 2026-10-04 on a desktop CPU with this model, on 10
         * query/passage pairs that share few words: with the mean pooling this app uses, the
         * right passage scored 0.50 to 0.66 and wrong ones 0.40 to 0.64, and the right one
         * ranked first for only 5/10. No floor keeps the right answers without admitting about
         * half the wrong ones. With first-token (CLS) pooling, which this model family is
         * built for, it was 8/10 with a clear gap, but that changes every stored vector and
         * needs a re-embed migration. Turn this on only if the ablation benchmark shows it helps.
         */
        const val SEMANTIC_PARENT_HITS = false

        /**
         * Cosine floor for [SEMANTIC_PARENT_HITS]. From the probe above: at 0.55 it kept 6/10
         * right answers and admitted 12/90 wrong ones; at 0.50, 10/10 and 47/90.
         */
        const val SEMANTIC_MIN_SIMILARITY = 0.55f

        /**
         * Return one row per document — its best-matching page — instead of one row per page.
         *
         * A document is stored as one row per chunk, and every lane and the fused list are
         * capped at ~50 rows. Counted in pages, one long PDF that mentions the query on 50
         * pages took every slot and no other PDF could appear (the "many PDFs don't come up in
         * search" report of 2026-10-04). Counted in documents, the same caps hold 50 different
         * files. Callers that want page-level hits (the answer path picks its source passages
         * from them) ask for `onePerDocument = false`.
         *
         * Not yet run on a phone or scored against golden queries; the ablation benchmark's
         * "perPage" variant is the previous behaviour.
         */
        const val ONE_PER_DOCUMENT = true

        /**
         * With [ONE_PER_DOCUMENT], BM25 chunk hits read per result slot before collapsing to
         * documents. BM25_BUDGET × this must not exceed the native engine's own cap
         * (SearchEngine::MAX_RESULTS, 300).
         */
        const val BM25_DOC_OVERFETCH = 6

        /**
         * Typo help only where it is needed. The typo lanes (the keyword engine's trigram
         * fallback and the fuzzy scan) exist for a word that is in the vault under a slightly
         * different spelling. Run for every word they also bring in real words that merely look
         * alike: typing "claude" listed every document that says "cloud" or "clause".
         *
         * With this on, a typed word that is in the vault as typed gets no typo help, and a
         * result that has none of the query's words is kept only when it has a near spelling
         * (or the root) of a typed word that is nowhere in the vault as typed. The cost: a
         * scan whose OCR misread a word ("invoic") is no longer found by the right spelling
         * once another document spells it right.
         *
         * Reported from a phone on 2026-10-07; not scored against golden queries. The ablation
         * variant "typoAlways" is the previous behaviour.
         */
        const val TYPO_HELP_ONLY_FOR_MISSING_WORDS = true

        /**
         * Added to a row in which the query's words stand next to each other in the order
         * typed — all of them for the full amount, a shorter run in proportion. A plural counts
         * as its singular. Without it "last working days" put a book with the three words
         * scattered over a page above the calendar that says "Last Working Day".
         *
         * Reported from a phone on 2026-10-07; not scored against golden queries. The ablation
         * variant "noWordOrder" is the previous behaviour.
         */
        const val WORDS_IN_ORDER_BONUS = 0.10

        /**
         * A row's stored text ends with a line of tags the indexers work out from fragments of
         * it ([com.amar.vault.retrieval.StoredText]): a page with "rs" on it is tagged
         * "receipt payment bill invoice", one with "exam" (or "example") "academic education".
         * A tag is found like any other word, which is the point of it, and was also scored
         * like one: a page tagged "invoice" earned what a page that says "invoice" earns, and
         * stood for its document in the list although another page of it says the word.
         *
         * With this on, the bonuses for a query's words go to the words a row says — on its
         * page or in its name — and a document is listed by a page that says the word before
         * one that is only tagged with it. Rows found by a tag alone are still listed, after
         * the ones that say the word.
         *
         * Seen on an emulator on 2026-10-07; not scored against golden queries. The ablation
         * variant "tagsAsText" is the previous behaviour.
         */
        const val PAGE_WORDS_BEFORE_TAGS = true
    }
}
