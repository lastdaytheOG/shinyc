package com.amar.vault.events.model

data class VirtualEvent(
    val eventId: String,
    val anchorType: String,
    val anchorConfidence: Float,
    val isEvidenceTruncated: Boolean,
    val evidenceCount: Int,
    val confirmedRelationshipCount: Int,
    val locationEvidenceCount: Int
)
