package com.amar.vault

object VaultConfig {
    object Embedding {
        const val DIM = 1024
        const val MAX_SEQ_LEN = 128

        const val QUERY_TRUE_LENGTH = true

        const val MODEL_ID = "bge-m3-ocr-int4"

        const val MODEL_VERSION = "1"

        const val SCHEMA_VERSION = 1
    }

    object Vector {
        const val MAX_ELEMENTS = 100_000
        const val DEFAULT_K = 20
        const val MAPPINGS_FILENAME = "vector_mappings.json"

        const val EMBEDDING_MANIFEST_FILENAME = "embedding_manifest.json"
    }

    object Chunking {
        const val CHUNK_SIZE = 200
        const val CHUNK_OVERLAP = 30

        const val DOC_TARGET_WORDS = 200
        const val DOC_OVERLAP_SENTENCES = 3
    }

    object Ocr {
        const val ML_KIT_MIN_LENGTH = 10
    }

    object Indexing {
        const val BATCH_SIZE = 8
        const val COOL_DOWN_MS = 2000L
    }

    object Retrieval {
        const val BM25_BUDGET = 50
        const val VECTOR_BUDGET = 50
        const val SUBSTRING_BUDGET = 50
        const val FUZZY_BUDGET = 30
        const val FTS_BUDGET = 50

        const val RRF_K = 60.0
        const val WEIGHT_BM25 = 1.2
        const val WEIGHT_VECTOR = 1.0
        const val WEIGHT_SUBSTRING = 1.1
        const val WEIGHT_FUZZY = 0.6

        const val LATE_SEMANTIC_WEIGHT = 0.5
        const val LATE_EMBED_SIM_THRESHOLD = 0.3
        const val LATE_EMBED_DOC_LIMIT = 10
        const val EXACT_PHRASE_BONUS = 0.10
        const val WORD_RATIO_BONUS = 0.06
        const val FUSION_TOPN = 50
        const val RERANK_TOPN = 100

        const val CANDIDATE_BOUNDED = false

        const val RANK_ORDER_HYDRATION = true

        const val SEMANTIC_PARENT_HITS = false

        const val SEMANTIC_MIN_SIMILARITY = 0.55f

        const val ONE_PER_DOCUMENT = true

        const val BM25_DOC_OVERFETCH = 6

        const val TYPO_HELP_ONLY_FOR_MISSING_WORDS = true

        const val WORDS_IN_ORDER_BONUS = 0.10

        const val PAGE_WORDS_BEFORE_TAGS = true

        const val NAME_BONUS = 0.04

        const val WHOLE_WORDS_FIRST = true

        const val PHOTO_LANE = true

        const val PHOTO_BUDGET = 24

        const val PHOTO_MATCH_MARGIN = 0.05f

        const val PHOTO_MATCH_MARGIN_HINDI = 0.05f

        const val PHOTO_MATCH_MARGIN_ROMANISED = 0.04f

        const val PHOTO_BEST_GAP = 0.08f
    }
}
