package com.amar.vault.search.core

object SearchTokenizer {
    /**
     * Splits normalized text into searchable n-grams and tokens.
     */
    fun tokenize(normalizedText: String): Set<String> {
        if (normalizedText.isBlank()) return emptySet()
        val tokens = mutableSetOf<String>()
        val words = normalizedText.split(" ")
        
        // Single word tokens
        tokens.addAll(words)
        
        // Bi-grams (e.g. "instagram reel")
        for (i in 0 until words.size - 1) {
            tokens.add("${words[i]} ${words[i+1]}")
            // Concatenated for typo tolerance (e.g. "instagramreel")
            tokens.add("${words[i]}${words[i+1]}")
        }
        
        // Sub-word prefix generation for fast partial matching
        // e.g., "insta", "instag", "instagr"
        words.forEach { word ->
            if (word.length >= 3) {
                for (i in 3..word.length) {
                    tokens.add(word.substring(0, i))
                }
            }
        }
        
        return tokens
    }
}
