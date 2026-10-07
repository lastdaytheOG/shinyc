package com.amar.vault.indexing

import com.amar.vault.CanonicalReviewQueue
import com.amar.vault.ClassificationEngine
import com.amar.vault.ItemType
import com.amar.vault.MetadataCandidateBuffer
import com.amar.vault.MetadataExtractionEngine
import com.amar.vault.VaultMetadata

/**
 * Metadata + classification stage (image/OCR path).
 *
 * Pure computation — no DB writes (persistence is owned by IndexPersister). Verbatim
 * extraction of the block previously inline in IndexingPipeline.indexBitmap: regex/dictionary
 * metadata extraction, the SOURCE_TYPE row, and the score-based classifier's
 * DOCUMENT_CLASS/DOCUMENT_TYPE/CATEGORY rows. Single concrete class (no interface — no
 * polymorphism), per the standing interface rule.
 */
class MetadataStage {

    /** @return the metadata rows to persist, and the canonical review-queue items. */
    fun extract(
        vaultItemId: String,
        ocrText: String,
        itemType: ItemType,
    ): Pair<List<VaultMetadata>, List<CanonicalReviewQueue>> {
        val extractionResult = MetadataExtractionEngine.extract(vaultItemId, ocrText)
        val rawMetadataList = extractionResult.first.toMutableList()
        val reviewItemsList = extractionResult.second

        // 1. SOURCE_TYPE (multi-label supported via distinct type/value rows)
        rawMetadataList.add(
            VaultMetadata(
                vaultItemId = vaultItemId,
                type = "SOURCE_TYPE",
                value = itemType.stored.uppercase(),
                confidence = 1.0f,
                source = "system",
                extractionVersion = "v3"
            )
        )

        // 2. Score-based classification (reconstruct a pseudo-buffer for the engine)
        val evalBuffer = MetadataCandidateBuffer(vaultItemId)
        rawMetadataList.forEach { evalBuffer.addCandidate(it.type, it.value, it.confidence, it.source, it.numericValue, it.timestampValue) }
        val classification = ClassificationEngine.evaluate(ocrText, evalBuffer)

        rawMetadataList.add(
            VaultMetadata(
                vaultItemId = vaultItemId,
                type = "DOCUMENT_CLASS",
                value = classification.documentClass.name,
                confidence = classification.confidence,
                source = "classifier",
                extractionVersion = "v3"
            )
        )
        rawMetadataList.add(
            VaultMetadata(
                vaultItemId = vaultItemId,
                type = "DOCUMENT_TYPE",
                value = classification.documentType.name,
                confidence = classification.confidence,
                source = "classifier",
                extractionVersion = "v3"
            )
        )
        rawMetadataList.add(
            VaultMetadata(
                vaultItemId = vaultItemId,
                type = "CATEGORY",
                value = classification.category,
                confidence = classification.confidence,
                source = "classifier",
                extractionVersion = "v3"
            )
        )

        return rawMetadataList.toList() to reviewItemsList
    }
}
