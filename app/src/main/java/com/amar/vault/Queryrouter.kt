package com.amar.vault

import android.util.Log

/**
 * QueryRouter — classifies user queries into 3 execution tiers.
 *
 * Elite principle: Minimize LLM usage, maximize system intelligence.
 *
 * Tier 0 (REGEX): Extract direct answer from search results — no LLM needed.
 *   Examples: "last OTP", "devanshu phone number", "my UPI ID"
 *   Latency: ~5ms
 *   Coverage: ~40% of real user queries
 *
 * Tier 1 (SEARCH): Format search results as readable answer — no LLM needed.
 *   Examples: "show screenshots", "find payments", "recent documents"
 *   Latency: ~100ms (just search + formatting)
 *   Coverage: ~40% of queries
 *
 * Tier 2 (LLM): Requires reasoning — use Gemma/Qwen.
 *   Examples: "summarize this PDF", "compare my spending month vs month"
 *   Latency: 2-8 seconds
 *   Coverage: ~20% of queries
 */
object QueryRouter {

    private const val TAG = "QueryRouter"

    enum class QueryTier { TIER0_REGEX, TIER1_SEARCH, TIER2_LLM }

    data class RoutedQuery(
        val tier: QueryTier,
        val cleanedQuery: String,
        val temporalIntent: TemporalIntent?
    )

    // ─────────────────────────────────────────────────────────────
    // Tier 0: Direct extraction (regex handles it)
    // ─────────────────────────────────────────────────────────────
    private val TIER0_EXTRACTION_HINTS = listOf(
        // OTP queries
        "last otp", "my otp", "otp from", "get otp", "show otp",
        "verification code", "my code",

        // Contact extraction
        "phone of", "phone number of", "number of",
        "contact of", "contact number",
        "mobile of", "mobile number",

        // ID extraction
        "upi id", "upi of", "account number", "account no",
        "ifsc", "pan number", "aadhaar",

        // Specific amount
        "how much did i pay", "amount paid", "amount for",
    )

    // ─────────────────────────────────────────────────────────────
    // Tier 1: Search only (no reasoning needed)
    // ─────────────────────────────────────────────────────────────
    private val TIER1_SEARCH_STARTERS = listOf(
        "show", "find", "list", "search", "display",
        "give me", "pull up", "bring up",
        "recent", "latest", "newest",
        "all my", "my all",
    )

    private val TIER1_COUNTING = listOf(
        "how many", "count",
    )

    // ─────────────────────────────────────────────────────────────
    // Tier 2: LLM reasoning required
    // ─────────────────────────────────────────────────────────────
    private val TIER2_REASONING = listOf(
        // Summarization
        "summarize", "summary", "summarise", "tldr", "brief",
        "summary batao", "summarize karo",

        // Comparison
        "compare", "versus", "vs", "difference between",
        "compare karo", "difference batao",

        // Explanation
        "explain", "what is", "what are", "what does",
        "tell me about", "describe",
        "why", "how does", "how do",

        // Analysis / math / aggregation
        "total", "sum", "calculate", "compute",
        "average", "mean",
        "kitna spend", "kitna kharch", "kitne paise", "mera kharcha", "kitna payment", "kitna pay",
        "what did i buy", "what did i", "buy from", "purchases",
        "related to", "associated with", "belong to",

        // Multi-step
        " and then", " then ", " also ",
    )

    /**
     * Parse temporal intent and route the query.
     */
    fun route(query: String): RoutedQuery {
        val parseResult = TemporalParser.parse(query)
        
        // Use temporal intent if confidence is reasonable
        val useTemporal = parseResult.confidence >= 0.5f
        
        val finalQuery = if (useTemporal) parseResult.cleanedQuery else query
        val finalIntent = if (useTemporal) parseResult.intent else null
        
        val tier = classify(finalQuery)
        
        return RoutedQuery(tier, finalQuery, finalIntent)
    }

    /**
     * Classify a query into its execution tier.
     * Runs in <1ms — no I/O, just string matching.
     */
    fun classify(query: String): QueryTier {
        val q = query.lowercase().trim()
        if (q.isBlank()) return QueryTier.TIER1_SEARCH

        val wordCount = q.split(Regex("\\s+")).size

        // ── Tier 2 triggers (highest priority — complex queries) ──
        if (TIER2_REASONING.any { q.contains(it) }) {
            Log.d(TAG, "'$query' → TIER2_LLM (reasoning keyword)")
            return QueryTier.TIER2_LLM
        }

        // Long queries (>10 words) usually need reasoning
        if (wordCount > 10) {
            Log.d(TAG, "'$query' → TIER2_LLM (long query, $wordCount words)")
            return QueryTier.TIER2_LLM
        }

        // Multiple question marks or conjunctions = complex
        if (q.count { it == '?' } > 1) {
            Log.d(TAG, "'$query' → TIER2_LLM (multiple questions)")
            return QueryTier.TIER2_LLM
        }

        // ── Tier 0: extraction queries ──
        if (TIER0_EXTRACTION_HINTS.any { q.contains(it) }) {
            Log.d(TAG, "'$query' → TIER0_REGEX (extraction hint)")
            return QueryTier.TIER0_REGEX
        }

        // ── Tier 1: search-only queries ──
        if (TIER1_SEARCH_STARTERS.any { q.startsWith(it) }) {
            Log.d(TAG, "'$query' → TIER1_SEARCH (search starter)")
            return QueryTier.TIER1_SEARCH
        }

        if (TIER1_COUNTING.any { q.contains(it) }) {
            Log.d(TAG, "'$query' → TIER1_SEARCH (counting)")
            return QueryTier.TIER1_SEARCH
        }

        // Short queries (1-3 words) are usually search terms
        if (wordCount <= 3) {
            Log.d(TAG, "'$query' → TIER1_SEARCH (short query)")
            return QueryTier.TIER1_SEARCH
        }

        // Default: search (cheaper to try first, user can ask follow-up)
        Log.d(TAG, "'$query' → TIER1_SEARCH (default)")
        return QueryTier.TIER1_SEARCH
    }

    /**
     * Get a short tier description for UI display.
     */
    fun tierLabel(tier: QueryTier): String = when (tier) {
        QueryTier.TIER0_REGEX -> "⚡ instant"
        QueryTier.TIER1_SEARCH -> "🔍 search"
        QueryTier.TIER2_LLM -> "🧠 thinking"
    }
}