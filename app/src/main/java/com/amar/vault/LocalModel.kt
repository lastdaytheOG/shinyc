package com.amar.vault

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_models")
data class LocalModel(
    @PrimaryKey val modelId: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val requiredRamGb: Float,
    var status: String,          // PENDING, DOWNLOADING, DOWNLOADED, VERIFYING, READY, FAILED
    var downloadProgress: Int,    // 0 to 100
    var isEnabled: Boolean
)
