package com.amar.vault

import java.util.Calendar

/**
 * Parsed advanced-search operators extracted from a raw query.
 *
 * This is a thin, additive pre-parser for `key:value` operator syntax — a form `QueryPlanner`
 * does NOT handle (it matches free words, not operators). Operators are applied as a post-filter
 * over the EXISTING search results (existing itemType/timestamp/OCR/metadata); they never replace
 * the retrieval engine, ranking, or fusion. When no operators are present, [hasAny] is false and
 * callers must behave exactly as before.
 */
data class ParsedOperators(
    val cleanedQuery: String,
    val itemTypes: Set<String> = emptySet(),
    val after: Long? = null,
    val before: Long? = null,
    val requireOcr: Boolean = false,
    val source: String? = null,
    val entity: String? = null,
    val category: String? = null,
    /** Human-readable chips for the UI, in the order parsed. */
    val chips: List<String> = emptyList(),
) {
    val hasAny: Boolean
        get() = itemTypes.isNotEmpty() || after != null || before != null ||
            requireOcr || source != null || entity != null || category != null
}

object SearchOperators {

    /** Operator value → concrete VaultItem.itemType set. */
    private val typeAliases: Map<String, Set<String>> = mapOf(
        "pdf" to setOf("pdf"),
        "image" to setOf("photo", "screenshot"),
        "img" to setOf("photo", "screenshot"),
        "photo" to setOf("photo"),
        "screenshot" to setOf("screenshot"),
        "doc" to setOf("word"), "docx" to setOf("word"), "word" to setOf("word"),
        "xls" to setOf("excel"), "xlsx" to setOf("excel"), "excel" to setOf("excel"),
        "epub" to setOf("epub"), "book" to setOf("epub"),
        "text" to setOf("text"), "txt" to setOf("text"),
    )

    private val OP_REGEX = Regex("""(\w+):(\S+)""")

    fun parse(raw: String): ParsedOperators {
        val itemTypes = mutableSetOf<String>()
        var after: Long? = null
        var before: Long? = null
        var requireOcr = false
        var source: String? = null
        var entity: String? = null
        var category: String? = null
        val chips = mutableListOf<String>()

        var cleaned = raw
        for (m in OP_REGEX.findAll(raw)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2]
            val recognized = when (key) {
                "type", "ext" -> typeAliases[value.lowercase()]?.let { itemTypes.addAll(it); chips.add("$key:$value"); true } ?: false
                "after" -> parseDateStart(value)?.let { after = it; chips.add("after:$value"); true } ?: false
                "before" -> parseDateStart(value)?.let { before = it; chips.add("before:$value"); true } ?: false
                "has" -> if (value.equals("ocr", true)) { requireOcr = true; chips.add("has:ocr"); true } else false
                "source" -> { source = value; chips.add("source:$value"); true }
                "entity" -> { entity = value; chips.add("entity:$value"); true }
                "category" -> { category = value; chips.add("category:$value"); true }
                else -> false
            }
            if (recognized) cleaned = cleaned.replace(m.value, " ")
        }

        return ParsedOperators(
            cleanedQuery = cleaned.replace(Regex("\\s+"), " ").trim(),
            itemTypes = itemTypes,
            after = after,
            before = before,
            requireOcr = requireOcr,
            source = source,
            entity = entity,
            category = category,
            chips = chips,
        )
    }

    /** Parses yyyy / yyyy-MM / yyyy-MM-dd to the start-of-period epoch millis (null if unparseable). */
    private fun parseDateStart(value: String): Long? {
        val parts = value.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
        if (year < 1970 || year > 3000) return null
        val month = parts.getOrNull(1)?.toIntOrNull()?.let { it - 1 } ?: 0
        val day = parts.getOrNull(2)?.toIntOrNull() ?: 1
        return Calendar.getInstance().apply {
            clear()
            set(year, month.coerceIn(0, 11), day.coerceAtLeast(1), 0, 0, 0)
        }.timeInMillis
    }
}
