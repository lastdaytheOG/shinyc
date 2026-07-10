package com.amar.vault.pipeline.stages

import com.amar.vault.VaultMetadata
import com.amar.vault.pipeline.models.UniversalMetadata
import com.amar.vault.pipeline.models.MetadataField

object RepositoryMapper {
    /**
     * Maps the rich UniversalMetadata object into a list of VaultMetadata key-value rows
     * so that we do not have to modify the database schema.
     */
    fun mapToVaultMetadata(itemId: String, metadata: UniversalMetadata): List<VaultMetadata> {
        val mapped = mutableListOf<VaultMetadata>()

        fun addIfPresent(type: String, field: MetadataField<*>?) {
            if (field != null) {
                val valueStr = field.value.toString()
                val numeric = field.value as? Double ?: (field.value as? Number)?.toDouble()
                val timestamp = field.value as? Long
                
                mapped.add(
                    VaultMetadata(
                        vaultItemId = itemId,
                        type = type,
                        value = valueStr,
                        numericValue = numeric,
                        timestampValue = timestamp,
                        confidence = field.qualityScore,
                        source = field.source,
                        extractionVersion = "v3_universal"
                    )
                )
            }
        }

        addIfPresent("TITLE", metadata.title)
        addIfPresent("SUBTITLE", metadata.subtitle)
        addIfPresent("DESCRIPTION", metadata.description)
        addIfPresent("AUTHOR", metadata.author)
        addIfPresent("DOMAIN", metadata.domain)
        addIfPresent("CANONICAL_URL", metadata.canonicalUrl)
        addIfPresent("DURATION", metadata.duration)
        addIfPresent("PRICE", metadata.price)
        
        addIfPresent("AI_SUMMARY", metadata.aiSummary)
        addIfPresent("OCR_TEXT", metadata.ocrText)
        addIfPresent("SEMANTIC_CATEGORY", metadata.semanticCategory)

        return mapped
    }

    /**
     * Inverse maps a list of VaultMetadata back into a UniversalMetadata object.
     */
    fun fromVaultMetadata(metadataList: List<VaultMetadata>): UniversalMetadata {
        fun <T> findField(type: String, mapper: (VaultMetadata) -> T): MetadataField<T>? {
            val item = metadataList.find { it.type == type } ?: return null
            return MetadataField(mapper(item), item.source, item.confidence)
        }

        return UniversalMetadata(
            title = findField("TITLE") { it.value },
            subtitle = findField("SUBTITLE") { it.value },
            description = findField("DESCRIPTION") { it.value },
            author = findField("AUTHOR") { it.value },
            domain = findField("DOMAIN") { it.value },
            canonicalUrl = findField("CANONICAL_URL") { it.value },
            duration = findField("DURATION") { it.timestampValue ?: it.value.toLongOrNull() ?: 0L },
            price = findField("PRICE") { it.numericValue ?: it.value.toDoubleOrNull() ?: 0.0 },
            
            aiSummary = findField("AI_SUMMARY") { it.value },
            ocrText = findField("OCR_TEXT") { it.value },
            semanticCategory = findField("SEMANTIC_CATEGORY") { it.value }
        )
    }
}
