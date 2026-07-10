package com.amar.vault

enum class CanonicalizationDecision {
    RESOLVED,
    CANDIDATE,
    UNRESOLVED
}

data class CanonicalResolutionResult(
    val decision: CanonicalizationDecision,
    val canonicalId: String?,
    val confidence: Float,
    val rawText: String
)

data class CanonicalEntity(
    val id: String,
    val displayName: String,
    val entityType: String
)

data class EntityAlias(
    val alias: String,
    val canonicalId: String,
    val isExactMatch: Boolean = false
)

object CanonicalEntityRegistry {
    
    // In-memory static dictionary (Normally loaded from SQLite canonical_entities)
    val entities = listOf(
        CanonicalEntity("PHONEPE_CORP", "PhonePe", "PAYMENT_APP"),
        CanonicalEntity("AMAZON_CORP", "Amazon", "ORGANIZATION"),
        CanonicalEntity("SWIGGY_CORP", "Swiggy", "ORGANIZATION"),
        CanonicalEntity("IRCTC_CORP", "IRCTC", "ORGANIZATION"),
        CanonicalEntity("ZOMATO_CORP", "Zomato", "ORGANIZATION"),
        CanonicalEntity("UBER_CORP", "Uber", "ORGANIZATION"),
        CanonicalEntity("GPAY_CORP", "Google Pay", "PAYMENT_APP"),
        CanonicalEntity("CRED_CORP", "CRED", "PAYMENT_APP")
    ).associateBy { it.id }

    val aliasMap = listOf(
        // Exact aliases
        EntityAlias("phonepe pvt ltd", "PHONEPE_CORP", true),
        EntityAlias("amazon.in", "AMAZON_CORP", true),
        EntityAlias("google pay", "GPAY_CORP", true),
        
        // General dictionary
        EntityAlias("phonepe", "PHONEPE_CORP", false),
        EntityAlias("amazon", "AMAZON_CORP", false),
        EntityAlias("swiggy", "SWIGGY_CORP", false),
        EntityAlias("irctc", "IRCTC_CORP", false),
        EntityAlias("zomato", "ZOMATO_CORP", false),
        EntityAlias("uber", "UBER_CORP", false),
        EntityAlias("gpay", "GPAY_CORP", false),
        EntityAlias("googlepay", "GPAY_CORP", false),
        EntityAlias("cred", "CRED_CORP", false)
    )

    fun resolve(rawString: String): CanonicalResolutionResult {
        val trimmed = rawString.trim()
        val lower = trimmed.lowercase()
        
        // 1. Exact Alias Match
        val exactMatch = aliasMap.find { it.isExactMatch && it.alias == lower }
        if (exactMatch != null) {
            return CanonicalResolutionResult(CanonicalizationDecision.RESOLVED, exactMatch.canonicalId, 1.0f, trimmed)
        }

        // 2. Known Dictionary Match (Substring case-insensitive)
        val dictMatch = aliasMap.find { !it.isExactMatch && it.alias == lower }
        if (dictMatch != null) {
            return CanonicalResolutionResult(CanonicalizationDecision.RESOLVED, dictMatch.canonicalId, 0.98f, trimmed)
        }

        // 3. Normalized Match
        val normalized = lower.replace(Regex("[^a-z0-9]"), "")
        val normMatch = aliasMap.find { !it.isExactMatch && it.alias.replace(Regex("[^a-z0-9]"), "") == normalized }
        if (normMatch != null) {
            return CanonicalResolutionResult(CanonicalizationDecision.RESOLVED, normMatch.canonicalId, 0.96f, trimmed)
        }

        // 4. Safe Fuzzy Match
        if (normalized.length >= 6) {
            var bestMatch: EntityAlias? = null
            var bestDistance = Int.MAX_VALUE
            
            for (alias in aliasMap) {
                if (alias.isExactMatch) continue
                val normAlias = alias.alias.replace(Regex("[^a-z0-9]"), "")
                val dist = levenshtein(normalized, normAlias)
                if (dist < bestDistance) {
                    bestDistance = dist
                    bestMatch = alias
                }
            }
            
            if (bestMatch != null) {
                val normAlias = bestMatch.alias.replace(Regex("[^a-z0-9]"), "")
                val similarity = 1.0f - (bestDistance.toFloat() / maxOf(normalized.length, normAlias.length))
                
                if (similarity >= 0.90f) {
                    // Length >= 6 and similarity >= 90% is safe for RESOLVED
                    return CanonicalResolutionResult(CanonicalizationDecision.RESOLVED, bestMatch.canonicalId, similarity, trimmed)
                } else if (similarity >= 0.80f) {
                    return CanonicalResolutionResult(CanonicalizationDecision.CANDIDATE, bestMatch.canonicalId, similarity, trimmed)
                }
            }
        }

        return CanonicalResolutionResult(CanonicalizationDecision.UNRESOLVED, null, 0.0f, trimmed)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev
            prev = curr
            curr = t
        }
        return prev[b.length]
    }
}
