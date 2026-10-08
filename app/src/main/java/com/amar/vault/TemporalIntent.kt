package com.amar.vault

data class TemporalParseResult(
    val cleanedQuery: String,
    val intent: TemporalIntent?,
    val confidence: Float,
    /** The year and month words the range was read from ("2024", "march"), as typed. */
    val calendarTerms: List<String> = emptyList(),
    /** Each period and order that was read, with the words it was read from, in the order found. */
    val readings: List<Understood> = emptyList(),
)

data class TemporalIntent(
    val timeRange: TimeRange?,
    val sort: SortOrder?,
    val limit: Int?
)

data class TimeRange(
    val startTimeMs: Long,
    val endTimeMs: Long
)

enum class SortOrder { ASC, DESC }
