package com.amar.vault

/**
 * Sprint 4B.1 — structured, immutable representation of a query after acronym expansion.
 *
 * Replaces the ad-hoc string concatenation of Sprint 4B: expansion is computed exactly
 * once (by [AcronymDictionary.analyze]) into this value object, and each retrieval lane
 * decides how to consume it — BM25 / semantic embed use [joined], the text-presence gate
 * uses [gateTerms], logging uses [originalQuery] + [expandedTerms].
 *
 * This is a pure data holder — no Android, no retrieval logic, no acronym knowledge (that
 * stays owned by [AcronymDictionary]). It is deliberately generic so a future synonym /
 * translation layer can populate the same shape without touching retrieval.
 *
 * Field semantics (for input "AI"):
 *  - [originalQuery]         = "AI"      — the query as received (post-NFC), verbatim.
 *  - [normalizedQuery]       = "ai"      — lowercased + trimmed (the old `qLower`).
 *  - [expandedTerms]         = ["AI", "Artificial Intelligence"] — original first, then
 *                              one canonical expansion per recognized acronym token.
 *  - [gateTerms]             = {ai, artificial, intelligence} — distinct lowercased words
 *                              spanning original + expansions, for the presence gate.
 *  - [containsKnownAcronym]  = true      — whether any token was a recognized acronym.
 */
data class ExpandedQuery(
    val originalQuery: String,
    val normalizedQuery: String,
    val expandedTerms: List<String>,
    val gateTerms: Set<String>,
    val containsKnownAcronym: Boolean,
    /**
     * What each recognised abbreviation stands for, lowered: "one time password". A page has
     * the meaning when it says these words next to each other, in this order; a page that says
     * "one" somewhere and "time" somewhere else does not.
     */
    val meanings: List<String> = emptyList(),
    /** The abbreviations themselves, as typed, in step with [meanings]. */
    val abbreviations: List<String> = emptyList(),
) {
    /**
     * The single-string form fed to lanes that tokenize internally (BM25, embeddings).
     * Byte-for-byte equal in value to Sprint 4B's `expandedQuery`: the original leading
     * phrase followed by each expansion, so downstream exact-phrase logic is unaffected.
     */
    fun joined(): String = expandedTerms.joinToString(" ")
}
