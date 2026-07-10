package com.amar.vault.pipeline.models

data class MetadataField<T>(
    val value: T,
    val source: String,
    val qualityScore: Float
)

data class UniversalMetadata(
    // Standard Metadata
    val title: MetadataField<String>? = null,
    val subtitle: MetadataField<String>? = null,
    val description: MetadataField<String>? = null,
    val author: MetadataField<String>? = null,
    val domain: MetadataField<String>? = null,
    val canonicalUrl: MetadataField<String>? = null,
    val duration: MetadataField<Long>? = null,
    val price: MetadataField<Double>? = null,
    
    // Future AI Metadata
    val aiSummary: MetadataField<String>? = null,
    val aiTags: MetadataField<List<String>>? = null,
    val ocrText: MetadataField<String>? = null,
    val semanticCategory: MetadataField<String>? = null,
    
    // Raw fallback/intent data (No quality score needed as these are absolute truths from Intent)
    val rawUri: String? = null,
    val mimeType: String? = null,
    val localFile: String? = null
)
