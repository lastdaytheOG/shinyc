package com.amar.vault.search.core

import java.text.Normalizer

object SearchNormalizer {
    /**
     * Cleans up the string for tokenization:
     * - Lowercases
     * - Strips accents
     * - Trims whitespace
     * - Strips non-alphanumeric chars (excluding spaces)
     */
    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""
        
        var normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        normalized = Regex("\\p{InCombiningDiacriticalMarks}+").replace(normalized, "")
        
        return normalized
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
