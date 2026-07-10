package com.amar.vault

import org.json.JSONObject
import java.io.File

/**
 * Sprint 3B, Task 1 — embedding model identity & versioning.
 *
 * A small, immutable sidecar record describing which embedding model produced every
 * vector persisted on this device. The vault runs exactly one embedding space at a
 * time (one HNSW index + one mappings file), so identity is recorded once at the
 * store level rather than duplicated per vector — every stored embedding belongs to
 * the space this manifest describes.
 *
 * Ownership: written and verified ONLY by [VectorSearchManager.initialize]. Nothing
 * else writes this file.
 *
 * Semantics:
 *  - If no manifest exists, the current identity from [VaultConfig.Embedding] is
 *    adopted. Pre-3B installs already have vectors produced by that same model
 *    (it has never changed), so adoption is correct, not a guess.
 *  - If a manifest exists and matches the running identity, nothing happens.
 *  - If a manifest exists and does NOT match, the mismatch is logged. No behaviour
 *    changes today — this is the hook a future background re-embed migration keys
 *    off (see docs/ARCHITECTURE.md, "Migration strategy"). Vectors from the old
 *    space keep working because the running model is only ever changed together
 *    with a migration that rewrites the manifest.
 */
data class EmbeddingManifest(
    val embeddingModelId: String,
    val embeddingModelVersion: String,
    val embeddingSchemaVersion: Int,
    val dim: Int,
    val createdAtMs: Long,
) {
    fun matchesCurrent(): Boolean =
        embeddingModelId == VaultConfig.Embedding.MODEL_ID &&
        embeddingModelVersion == VaultConfig.Embedding.MODEL_VERSION &&
        embeddingSchemaVersion == VaultConfig.Embedding.SCHEMA_VERSION &&
        dim == VaultConfig.Embedding.DIM

    fun toJson(): JSONObject = JSONObject()
        .put(KEY_MODEL_ID, embeddingModelId)
        .put(KEY_MODEL_VERSION, embeddingModelVersion)
        .put(KEY_SCHEMA_VERSION, embeddingSchemaVersion)
        .put(KEY_DIM, dim)
        .put(KEY_CREATED_AT, createdAtMs)

    companion object {
        private const val KEY_MODEL_ID = "embeddingModelId"
        private const val KEY_MODEL_VERSION = "embeddingModelVersion"
        private const val KEY_SCHEMA_VERSION = "embeddingSchemaVersion"
        private const val KEY_DIM = "dim"
        private const val KEY_CREATED_AT = "createdAtMs"

        fun current(): EmbeddingManifest = EmbeddingManifest(
            embeddingModelId = VaultConfig.Embedding.MODEL_ID,
            embeddingModelVersion = VaultConfig.Embedding.MODEL_VERSION,
            embeddingSchemaVersion = VaultConfig.Embedding.SCHEMA_VERSION,
            dim = VaultConfig.Embedding.DIM,
            createdAtMs = System.currentTimeMillis(),
        )

        fun fromJson(json: JSONObject): EmbeddingManifest = EmbeddingManifest(
            embeddingModelId = json.getString(KEY_MODEL_ID),
            embeddingModelVersion = json.getString(KEY_MODEL_VERSION),
            embeddingSchemaVersion = json.getInt(KEY_SCHEMA_VERSION),
            dim = json.getInt(KEY_DIM),
            createdAtMs = json.optLong(KEY_CREATED_AT, 0L),
        )

        fun load(file: File): EmbeddingManifest? {
            if (!file.exists()) return null
            return try {
                fromJson(JSONObject(file.readText()))
            } catch (e: Exception) {
                VaultLog.w("EmbeddingManifest", "Unreadable manifest, will re-adopt: ${e.message}")
                null
            }
        }

        /**
         * Idempotent: loads the manifest, adopting the current identity if the file is
         * missing or unreadable. Returns the manifest that now describes the store.
         */
        fun ensure(file: File): EmbeddingManifest {
            load(file)?.let { existing ->
                if (!existing.matchesCurrent()) {
                    VaultLog.w(
                        "EmbeddingManifest",
                        "Stored embedding space ${existing.embeddingModelId}/" +
                        "${existing.embeddingModelVersion}/s${existing.embeddingSchemaVersion} " +
                        "differs from running ${VaultConfig.Embedding.MODEL_ID}/" +
                        "${VaultConfig.Embedding.MODEL_VERSION}/s${VaultConfig.Embedding.SCHEMA_VERSION}" +
                        " — background re-embed migration required (not yet implemented)"
                    )
                }
                return existing
            }
            val adopted = current()
            try {
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(adopted.toJson().toString())
                if (file.exists()) file.delete()
                tmp.renameTo(file)
            } catch (e: Exception) {
                VaultLog.w("EmbeddingManifest", "Failed to persist manifest: ${e.message}")
            }
            return adopted
        }
    }
}
