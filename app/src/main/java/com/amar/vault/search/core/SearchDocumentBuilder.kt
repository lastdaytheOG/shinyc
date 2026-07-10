package com.amar.vault.search.core

import com.amar.vault.search.models.SearchDocument
import com.amar.vault.ui.renderengine.models.RichSavedItem

object SearchValidator {
    fun isValid(item: RichSavedItem): Boolean {
        // Items must have a valid ID and at least some content to be indexed
        return item.id.isNotBlank() && (item.title.isNotBlank() || item.rawMetadata.canonicalUrl?.value?.isNotBlank() == true)
    }
}

object SearchDocumentBuilder {
    fun build(item: RichSavedItem): SearchDocument? {
        if (!SearchValidator.isValid(item)) return null
        
        val meta = item.rawMetadata
        val stashItem = item.rawStashItem

        // Assemble raw string for tokenization
        val contentBuilder = StringBuilder()
        contentBuilder.append("${item.title} ")
        item.subtitle?.let { contentBuilder.append("$it ") }
        meta.description?.value?.let { contentBuilder.append("$it ") }
        meta.domain?.value?.let { contentBuilder.append("$it ") }
        stashItem.userNote?.let { contentBuilder.append("$it ") }
        
        // Tags
        val tags = stashItem.category.let { if (it.isNotBlank()) listOf(it) else emptyList() }
        tags.forEach { contentBuilder.append("$it ") }

        // Normalize and Tokenize
        val normalizedText = SearchNormalizer.normalize(contentBuilder.toString())
        val tokens = SearchTokenizer.tokenize(normalizedText)

        return SearchDocument(
            id = item.id,
            title = item.title,
            subtitle = item.subtitle,
            description = meta.description?.value,
            domain = meta.domain?.value,
            platform = null, // Extracted in future AI mapping
            creator = meta.author?.value,
            folder = stashItem.category,
            tags = tags,
            notes = stashItem.userNote,
            filename = stashItem.sourceFile,
            createdAt = stashItem.savedAt,
            updatedAt = stashItem.savedAt,
            tokens = tokens,
            richItem = item
        )
    }
}
