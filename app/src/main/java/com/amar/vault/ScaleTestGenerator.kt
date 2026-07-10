package com.amar.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ScaleTier(val docCount: Int, val metaCount: Int, val relCount: Int, val eventCount: Int) {
    SMALL(1_000, 5_000, 500, 50),
    MEDIUM(10_000, 50_000, 5_000, 500),
    LARGE(100_000, 500_000, 50_000, 5_000)
}

object ScaleTestGenerator {
    private const val CHUNK_SIZE = 1000

    suspend fun runScaleTest(db: VaultDatabase, tier: ScaleTier) = withContext(Dispatchers.IO) {
        println("Starting Scale Test for tier: ${tier.name}")
        
        // 1. Inject Documents in Chunks
        for (i in 0 until tier.docCount step CHUNK_SIZE) {
            val chunk = (i until minOf(i + CHUNK_SIZE, tier.docCount)).map {
                VaultItem(
                    id = "doc_$it",
                    uri = "content://dummy/$it",
                    ocrText = "Generated scale document $it",
                    lang = "en",
                    itemType = "PDF",
                    timestamp = System.currentTimeMillis()
                )
            }
            db.vaultDao().insertAll(chunk)
        }

        // 2. Inject Metadata in Chunks
        for (i in 0 until tier.metaCount step CHUNK_SIZE) {
            val chunk = (i until minOf(i + CHUNK_SIZE, tier.metaCount)).map {
                VaultMetadata(
                    id = 0, // autoGenerate
                    vaultItemId = "doc_${it % tier.docCount}",
                    type = "KEY_${it % 10}",
                    value = "VALUE_$it",
                    confidence = 0.95f,
                    source = "SCALE_GENERATOR",
                    extractionVersion = "v1"
                )
            }
            db.vaultMetadataDao().insertAll(chunk)
        }

        // 3. Inject Relationships in Chunks
        for (i in 0 until tier.relCount step CHUNK_SIZE) {
            val chunk = (i until minOf(i + CHUNK_SIZE, tier.relCount)).map {
                VaultRelationship(
                    sourceId = "doc_${it % tier.docCount}",
                    targetId = "doc_${(it + 1) % tier.docCount}",
                    relationshipType = "PAID_FOR",
                    confidence = 0.98f,
                    relationshipState = "CONFIRMED",
                    createdByRule = "SCALE_GENERATOR",
                    relationshipVersion = "v1",
                    relationshipEvidenceJson = "[]"
                )
            }
            db.vaultRelationshipDao().insertAll(chunk)
        }

        // Note: Event insertion would use EventSnapshotDao chunked
        
        println("Scale Test Injection Complete for tier: ${tier.name}")
    }
}
