package com.amar.vault.pipeline.stages

import com.amar.vault.pipeline.models.UniversalMetadata
import com.amar.vault.pipeline.models.MetadataField

object MetadataMerger {
    
    /**
     * Intelligently combines multiple UniversalMetadata objects into one.
     * Higher quality scores overwrite lower quality scores.
     */
    fun merge(metadataList: List<UniversalMetadata>): UniversalMetadata {
        var merged = UniversalMetadata()

        for (metadata in metadataList) {
            merged = UniversalMetadata(
                title = resolve(merged.title, metadata.title),
                subtitle = resolve(merged.subtitle, metadata.subtitle),
                description = resolve(merged.description, metadata.description),
                author = resolve(merged.author, metadata.author),
                domain = resolve(merged.domain, metadata.domain),
                canonicalUrl = resolve(merged.canonicalUrl, metadata.canonicalUrl),
                duration = resolve(merged.duration, metadata.duration),
                price = resolve(merged.price, metadata.price),
                
                aiSummary = resolve(merged.aiSummary, metadata.aiSummary),
                aiTags = resolve(merged.aiTags, metadata.aiTags),
                ocrText = resolve(merged.ocrText, metadata.ocrText),
                semanticCategory = resolve(merged.semanticCategory, metadata.semanticCategory),
                
                rawUri = metadata.rawUri ?: merged.rawUri,
                mimeType = metadata.mimeType ?: merged.mimeType,
                localFile = metadata.localFile ?: merged.localFile
            )
        }

        return merged
    }

    private fun <T> resolve(current: MetadataField<T>?, incoming: MetadataField<T>?): MetadataField<T>? {
        if (current == null) return incoming
        if (incoming == null) return current
        
        return if (incoming.qualityScore > current.qualityScore) incoming else current
    }
}
