package com.amar.vault.retrieval

/**
 * Immutable metadata-boost weights, injected into the retrieval fusion stage.
 *
 * Replaces the former `MetadataBoostConfig` global `object` whose `var` fields were
 * hidden, process-wide mutable state. Values are frozen at the prior defaults, so
 * ranking is unchanged. Provided as a @Singleton via VaultModule.
 */
data class BoostConfig(
    val organization: Float = 1.5f,
    val sourceApp: Float = 1.4f,
    val paymentApp: Float = 1.5f,
    val documentType: Float = 1.3f,
    val defaultSoft: Float = 1.2f,
    val category: Float = 1.6f,
)
