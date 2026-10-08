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
    val itemTypes: Set<ItemType> = emptySet(),
    val after: Long? = null,
    val before: Long? = null,
    val requireOcr: Boolean = false,
    val source: String? = null,
    val entity: String? = null,
    val category: String? = null,
    /** Human-readable chips for the UI, in the order parsed. */
    val chips: List<String> = emptyList(),
    /** The same filters as readings the screen can show and the user can take out. */
    val understood: List<Understood> = emptyList(),
) {
    val hasAny: Boolean
        get() = itemTypes.isNotEmpty() || after != null || before != null ||
            requireOcr || source != null || entity != null || category != null
}

object SearchOperators {

    /** Operator value → the item types it means. */
    private val typeAliases: Map<String, Set<ItemType>> = mapOf(
        "pdf" to setOf(ItemType.PDF),
        "image" to setOf(ItemType.PHOTO, ItemType.SCREENSHOT),
        "img" to setOf(ItemType.PHOTO, ItemType.SCREENSHOT),
        "photo" to setOf(ItemType.PHOTO),
        "screenshot" to setOf(ItemType.SCREENSHOT),
        "doc" to setOf(ItemType.WORD), "docx" to setOf(ItemType.WORD), "word" to setOf(ItemType.WORD),
        "xls" to setOf(ItemType.EXCEL), "xlsx" to setOf(ItemType.EXCEL), "excel" to setOf(ItemType.EXCEL),
        "epub" to setOf(ItemType.EPUB), "book" to setOf(ItemType.EPUB),
        "text" to setOf(ItemType.TEXT), "txt" to setOf(ItemType.TEXT),
    )

    private val OP_REGEX = Regex("""(\w+):(\S+)""")

    fun parse(raw: String): ParsedOperators {
        val itemTypes = mutableSetOf<ItemType>()
        var after: Long? = null
        var before: Long? = null
        var requireOcr = false
        var source: String? = null
        var entity: String? = null
        var category: String? = null
        val chips = mutableListOf<String>()
        val understood = mutableListOf<Understood>()

        var cleaned = raw
        for (m in OP_REGEX.findAll(raw)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2]
            val chipsBefore = chips.size
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
            if (chips.size > chipsBefore) understood += Understood(Understood.Kind.FILTER, m.value, labelOf(key, value))
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
            understood = understood,
        )
    }

    /** What a filter means, in words: `type:pdf` is "PDFs only". */
    private fun labelOf(key: String, value: String): String = when (key) {
        "type", "ext" -> when (value.lowercase()) {
            "pdf" -> "PDFs only"
            "image", "img" -> "Pictures only"
            "photo" -> "Photos only"
            "screenshot" -> "Screenshots only"
            "doc", "docx", "word" -> "Word files only"
            "xls", "xlsx", "excel" -> "Excel files only"
            "epub", "book" -> "Books only"
            else -> "Text only"
        }
        "after" -> "After $value"
        "before" -> "Before $value"
        "has" -> "With text on it"
        "source" -> "From $value"
        "entity" -> "About $value"
        else -> "In folder $value"
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
