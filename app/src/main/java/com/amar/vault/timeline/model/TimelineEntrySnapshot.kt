package com.amar.vault.timeline.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_entry_snapshot",
    indices = [
        Index("timelineId"),
        Index("timestamp"),
        Index("entryType")
    ]
)
data class TimelineEntrySnapshot(
    @PrimaryKey val entryId: String,
    val timelineId: String,
    val timestamp: Long?,
    val entryType: String,
    val confidence: Float,
    val state: String,
    val title: String,
    val evidenceIdsJson: String
)
