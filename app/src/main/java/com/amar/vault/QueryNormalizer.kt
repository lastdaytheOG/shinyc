package com.amar.vault

object QueryNormalizer {

    /**
     * Translates, normalizes, and expands a raw query into a list of query variants
     * to run in parallel against the retrieval engines.
     * Deduplicates results and caps variants between 3 and 5 (maximum 4) to protect CPU.
     */
    fun normalize(query: String): List<String> {
        val q = query.lowercase().trim()
        if (q.isBlank()) return emptyList()

        val variants = mutableListOf<String>()
        variants.add(query.trim()) // Always preserve original query

        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }

        // 1. Transliteration mapping: Swap Devanagari Hindi to English words
        val transliterations = mapOf(
            "दिल्ली" to "delhi",
            "यात्रा" to "yatra",
            "सफर" to "yatra",
            "टिकट" to "ticket",
            "रसीद" to "receipt",
            "रसीदें" to "receipt",
            "बिल" to "bill",
            "अमेज़न" to "amazon",
            "अमेजन" to "amazon",
            "स्विगी" to "swiggy",
            "जोमैटो" to "zomato",
            "ज़ोमैटो" to "zomato",
            "भुगतान" to "payment",
            "खर्च" to "spend",
            "खर्चा" to "spend",
            "पैसे" to "money",
            "बैंक" to "bank",
            "स्टेटमेंट" to "statement",
            "कल" to "yesterday",
            "आज" to "today"
        )

        val replacedWords = words.map { transliterations[it] ?: it }
        val transliteratedQuery = replacedWords.joinToString(" ")
        if (transliteratedQuery != q) {
            variants.add(transliteratedQuery)
        }

        // 2. Hinglish filler word stripping: Romanized Hindi grammar removal
        val hinges = mapOf(
            "mera" to "", "meri" to "", "mere" to "", "dikhao" to "", 
            "dikhata" to "", "dikhaye" to "", "kahan" to "", "ka" to "", 
            "ki" to "", "ke" to "", "wala" to "", "wale" to "", 
            "wali" to "", "se" to "", "par" to "", "ko" to "", 
            "hai" to "", "tha" to "", "sath" to "", "saath" to "", 
            "leke" to "", "lekar" to "", "karo" to "", "batao" to ""
        )

        val cleanedWords = replacedWords.map { hinges[it] ?: it }.filter { it.isNotBlank() }
        val cleanedQuery = cleanedWords.joinToString(" ")
        if (cleanedQuery.isNotBlank() && cleanedQuery != q && cleanedQuery != transliteratedQuery) {
            variants.add(cleanedQuery)
        }

        // 3. Phrase-level synonym expansion
        if (q.contains("payment") || q.contains("transaction") || q.contains("paytm") || q.contains("phonepe") || q.contains("gpay")) {
            if (q.contains("issue") || q.contains("failed") || q.contains("fail") || q.contains("pending") || q.contains("error") || q.contains("problem")) {
                variants.add("payment issue")
                variants.add("payment failed")
                variants.add("payment problem")
            }
        }

        if (q.contains("amazon") || q.contains("bill") || q.contains("receipt") || q.contains("invoice") || q.contains("rasid") || q.contains("रसीद")) {
            if (q.contains("amazon") || q.contains("order")) {
                variants.add("amazon bill")
                variants.add("amazon receipt")
                variants.add("amazon invoice")
            }
        }

        if (q.contains("delhi") || q.contains("दिल्ली") || q.contains("yatra") || q.contains("travel") || q.contains("trip") || q.contains("यात्रा")) {
            if (q.contains("ticket") || q.contains("boarding") || q.contains("flight") || q.contains("train") || q.contains("टिकट")) {
                variants.add("delhi yatra ticket")
                variants.add("delhi travel ticket")
                variants.add("delhi trip ticket")
            }
        }

        // 4. Generic synonym replacements (if variants list is small)
        if (variants.size < 4) {
            val genericExpansions = mutableListOf<String>()
            for (v in variants) {
                if (v.contains("bill") && !v.contains("receipt")) {
                    genericExpansions.add(v.replace("bill", "receipt"))
                }
                if (v.contains("receipt") && !v.contains("invoice")) {
                    genericExpansions.add(v.replace("receipt", "invoice"))
                }
                if (v.contains("ticket") && !v.contains("booking")) {
                    genericExpansions.add(v.replace("ticket", "booking"))
                }
            }
            variants.addAll(genericExpansions)
        }

        // Enforce the deduplication and 4-variant cap (3-5 variants range) guardrail
        return variants.distinct().take(4)
    }
}
