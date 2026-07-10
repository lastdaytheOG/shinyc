package com.amar.vault.timeline.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timeline_snapshot")
data class TimelineSnapshot(
    @PrimaryKey val timelineId: String,
    val scope: String,
    val state: String,
    val timelineVersion: String,
    val generatedAt: Long,
    val timelineContextHash: String
)
