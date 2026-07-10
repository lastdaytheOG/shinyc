package com.amar.vault

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

enum class SourceType {
    ANDROID_SHARE, CAMERA, FILE_IMPORT, DESKTOP_SYNC, CLOUD_SYNC, CLIPBOARD, BROWSER_EXTENSION, EMAIL_IMPORT
}

enum class SessionStatus {
    CAPTURING, PARTIAL_SUCCESS, READY, ENRICHING, COMPLETE, FAILED
}

enum class AttachmentStatus {
    RECEIVED, VALIDATED, COPIED, METADATA_EXTRACTED, READY, ENRICHING, COMPLETE, FAILED
}

enum class AttachmentError {
    NONE, PERMISSION_DENIED, COPY_FAILED, READ_FAILED, HASH_FAILED, INVALID_URI, UNSUPPORTED_MIME, METADATA_FAILED, DATABASE_FAILED, WORKER_FAILED, ENRICHMENT_FAILED
}

enum class OpenStrategy {
    OPEN_WEB, OPEN_PLAY_STORE, OPEN_INSTAGRAM, OPEN_YOUTUBE, OPEN_PDF, OPEN_IMAGE, OPEN_VIDEO, OPEN_AUDIO, OPEN_FILE, OPEN_NATIVE_FALLBACK
}

enum class CaptureStatus { SUCCESS, PARTIAL_SUCCESS, FAILED }

@Entity(tableName = "ingestion_sessions")
data class IngestionSession(
    @PrimaryKey val id: String,
    val sourceType: SourceType,
    val action: String,
    val type: String?,
    val sourcePackage: String,
    val timestamp: Long,
    val status: SessionStatus
)

@Entity(
    tableName = "ingestion_attachments",
    foreignKeys = [
        ForeignKey(
            entity = IngestionSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class IngestionAttachment(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val sessionId: String,
    val status: AttachmentStatus,
    val errorCode: AttachmentError,
    val attachmentType: String, // "STREAM", "TEXT", etc.
    val originalUri: String?,
    val localPath: String?,
    val mimeType: String,
    val filename: String?,
    val contentHash: String?,
    val width: Int?,
    val height: Int?,
    val duration: Long?,
    val fileSize: Long?,
    val pageCount: Int?,
    val domain: String?,
    val artist: String?,
    val album: String?,
    val latitude: Double?,
    val longitude: Double?,
    val thumbnailPath: String?,
    val previewTitle: String?,
    val rawExtrasJson: String?
)

data class ContentResolution(
    val primaryAttachment: IngestionAttachment,
    val secondaryAttachments: List<IngestionAttachment>,
    val previewType: String,
    val recommendedOpenStrategy: OpenStrategy,
    val previewTitle: String?,
    val previewThumbnailPath: String?
)

data class CaptureResult(
    val status: CaptureStatus,
    val sessionId: String,
    val successfulAttachments: List<IngestionAttachment>,
    val failedAttachments: List<IngestionAttachment>,
    val stashItemId: String?
)
