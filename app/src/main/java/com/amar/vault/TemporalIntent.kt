package com.amar.vault

data class TemporalParseResult(
    val cleanedQuery: String,
    val intent: TemporalIntent?,
    val confidence: Float
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
