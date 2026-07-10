package com.amar.vault

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val sources: List<VaultItem> = emptyList(),
    val knowledgeCard: KnowledgeCard? = null,
    val collectionCard: CollectionCard? = null
)
