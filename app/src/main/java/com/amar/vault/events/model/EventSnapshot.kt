package com.amar.vault.events.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "event_snapshot",
    indices = [
        Index("snapshotState"),
        Index("anchorType"),
        Index("eventBuildContextHash")
    ]
)
data class EventSnapshot(
    @PrimaryKey val eventId: String,
    val anchorType: String,
    val anchorConfidence: Float,
    val isEvidenceTruncated: Boolean,
    val evidenceCount: Int,
    val confirmedRelationshipCount: Int,
    val locationEvidenceCount: Int,
    val evidenceIdsJson: String,
    val buildTimeMs: Long,
    
    val retryCount: Int = 0,
    val lastFailureReason: String? = null,
    
    val eventBuildContextHash: String,
    val relationshipVersion: String,
    val classificationVersion: String,
    val canonicalizationVersion: String,
    val eventVersion: String,
    val snapshotState: EventSnapshotState
)
