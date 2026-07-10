package com.amar.vault

import android.net.Uri

/**
 * Shared, side-effect-free helpers for pulling an openable URL out of arbitrary
 * shared text and for normalizing URLs so that the same logical link dedups to
 * the same content hash.
 *
 * Used by both the capture path (ContentPriorityResolver, ShareHandlerActivity)
 * and the open path (OpenStrategyResolver) so URL handling is defined once.
 *
 * Isolated to the Share / Saved feature. No Android framework state is touched
 * beyond [Uri] parsing.
 */
object ShareUrlExtractor {

    // Matches the first web/app link inside a larger blob of text, e.g.
    // "Check this reel 😍 https://www.instagram.com/reel/xyz?igshid=..."
    private val URL_REGEX = Regex(
        "(intent://\\S+|android-app://\\S+|market://\\S+|spotify:\\S+|https?://\\S+)",
        RegexOption.IGNORE_CASE
    )

    // Query params that are pure tracking noise and must not affect dedup.
    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "fbclid", "gclid", "igshid", "igsh", "si", "feature",
        "ref", "ref_src", "ref_url", "source", "spm", "_branch_match_id"
    )

    /**
     * Returns the first openable URL found anywhere in [text], trimmed of
     * trailing punctuation, or null if none. A bare URL is returned as-is.
     */
    fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val match = URL_REGEX.find(trimmed) ?: return null
        return match.value.trimEnd('.', ',', ')', ']', '}', '"', '\'', '>', ';')
    }

    /** True if [text] contains at least one openable URL. */
    fun containsUrl(text: String?): Boolean = extractFirstUrl(text) != null

    /** Host of the first URL in [text], lowercased, or null. */
    fun extractDomain(text: String?): String? {
        val url = extractFirstUrl(text) ?: return null
        return runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()
    }

    /**
     * Produces a stable key for a URL for duplicate detection: lowercases the
     * scheme+host, drops the fragment, and removes known tracking parameters
     * while preserving meaningful ones (e.g. a YouTube `v=` id). Falls back to
     * the raw input if the URL cannot be parsed. Conservative by design — it
     * never collapses two genuinely different links.
     */
    fun normalizeForHash(rawUrl: String): String {
        val url = extractFirstUrl(rawUrl) ?: return rawUrl.trim()
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
        val scheme = uri.scheme?.lowercase() ?: return url
        if (scheme != "http" && scheme != "https") return url // don't touch market:/intent:/spotify:

        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return url
        val path = uri.path?.trimEnd('/').orEmpty()

        val keptParams = runCatching {
            uri.queryParameterNames
                .filter { it.lowercase() !in TRACKING_PARAMS }
                .sorted()
                .mapNotNull { name -> uri.getQueryParameter(name)?.let { "$name=$it" } }
        }.getOrDefault(emptyList())

        val query = if (keptParams.isEmpty()) "" else "?" + keptParams.joinToString("&")
        return "$scheme://$host$path$query"
    }
}
