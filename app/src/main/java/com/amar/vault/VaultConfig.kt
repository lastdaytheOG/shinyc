package com.amar.vault

object VaultConfig {
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
        const val MEANING_BUDGET = 60
        const val SUBSTRING_BUDGET = 50
        const val FUZZY_BUDGET = 30
        const val FTS_BUDGET = 50

        const val RRF_K = 60.0
        const val WEIGHT_BM25 = 1.2
        const val WEIGHT_SUBSTRING = 1.1
        const val WEIGHT_FUZZY = 0.6

        const val EXACT_PHRASE_BONUS = 0.10
        const val WORD_RATIO_BONUS = 0.06
        const val FUSION_TOPN = 50
        const val RERANK_TOPN = 100

        const val CANDIDATE_BOUNDED = true

        const val RANK_ORDER_HYDRATION = true

        const val MEANING_LANE = true

        const val HINGLISH_BRIDGE = true
        const val HINGLISH_SAID = true
        const val HINGLISH_MODEL = true

        const val FACTS_LANE = true

        const val OPENED_BONUS = 0.03

        const val FACT_ID_CHANCE = 0.97

        const val FACT_AMOUNT_CHANCE = 0.6

        const val FACT_SURE_AT = 0.95

        const val MEANING_DEADLINE_MS = 2500L

        const val MEANING_KEEP_STANDING = 4.0f

        const val MEANING_KEEP_MARGIN = 0.06f

        const val MEANING_GAP = 0.04f

        const val MEANING_SURE_STANDING = 6.0f

        const val MEANING_SURE_LEAD = 0.0f

        const val MEANING_SHOWN = 10

        const val MEANING_SPREAD_MIN_ROWS = 200

        const val MEANING_SMALL_VAULT_LEAD = 0.05f

        const val MEANING_SMALL_VAULT_MARGIN = -0.03f

        const val SAYS_BIAS = -3.5844
        const val SAYS_COVERAGE = 5.2243
        const val SAYS_SURE_AT = 1.01
        const val LOOKS_BIAS = -7.6766
        const val LOOKS_MARGIN = 7.2518
        const val LOOKS_SCORE = 12.7806
        const val LOOKS_SURE_AT = 1.01
        const val MEANING_BIAS = -2.4747
        const val MEANING_STANDING = 0.6861
        const val MEANING_MARGIN = 1.7126
        const val MEANING_SURE_AT = 1.01

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
