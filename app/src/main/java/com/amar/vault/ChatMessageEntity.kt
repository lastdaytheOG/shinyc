package com.amar.vault

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "chat_history",
    indices = [Index(value = ["sessionId", "timestamp"])]
)
data class ChatMessageEntity(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long,
    val sourcesJson: String = "[]", // JSON array of serialized VaultItem objects or IDs
    val knowledgeCardJson: String? = null,
    val collectionCardJson: String? = null
)
