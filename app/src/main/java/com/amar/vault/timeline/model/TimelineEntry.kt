package com.amar.vault.timeline.model

data class TimelineEntry(
    val id: String,
    val timestamp: Long?,
    val entryType: TimelineEntryType,
    val state: TimelineState,
    val confidence: Float,
    val title: String,
    val evidenceIds: List<String>
)
