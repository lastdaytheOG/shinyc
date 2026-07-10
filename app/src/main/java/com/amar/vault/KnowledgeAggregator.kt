package com.amar.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context

data class KnowledgeCard(
    val title: String,
    val count: Int,
    val totalValue: Double,
    val lastActionDate: Long,
    val confidence: Float,
    val evidenceIds: List<String>,
    val evidenceCount: Int
)

class KnowledgeAggregator(private val context: Context) {
    
    suspend fun buildCard(canonicalId: String): KnowledgeCard? = withContext(Dispatchers.IO) {
        val db = VaultDatabase.get(context)
        val metaDao = db.vaultMetadataDao()
        val statsDao = db.canonicalEntityStatsDao()
        
        val canonicalEntity = CanonicalEntityRegistry.entities[canonicalId] ?: return@withContext null
        val entityType = canonicalEntity.entityType
        
        // 1. Get Aggregation Data using the optimized INNER JOIN query
        val agg = metaDao.getAggregationForCanonicalEntity(canonicalId, entityType, "AMOUNT")
            ?: return@withContext null
            
        if (agg.amountCount == 0) return@withContext null
        
        // 2. Get Canonicalization Confidence
        val stats = statsDao.getStats(canonicalId)
        val canonicalConfidence = stats?.averageConfidence ?: 1.0f
        val totalEntityDocs = stats?.documentCount ?: agg.amountCount
        
        // 3. Completeness (What % of these entities actually had an AMOUNT extracted?)
        val completeness = minOf(1.0f, agg.amountCount.toFloat() / maxOf(1, totalEntityDocs))
        
        // 4. Per-Card Confidence Strategy
        val cardConfidence = agg.averageAmountConfidence * canonicalConfidence * completeness
        
        // If confidence < 0.80, do not render card
        if (cardConfidence < 0.80f) return@withContext null
        
        // 5. Get Last Action Date
        val lastActionDate = metaDao.getLastActionDate(canonicalId) ?: 0L
        
        // 6. Evidence Check
        val evidenceIds = agg.evidenceIds
        if (evidenceIds.isEmpty()) return@withContext null
        
        KnowledgeCard(
            title = canonicalEntity.displayName,
            count = agg.amountCount,
            totalValue = agg.totalValue,
            lastActionDate = lastActionDate,
            confidence = cardConfidence,
            evidenceIds = evidenceIds,
            evidenceCount = evidenceIds.size
        )
    }
}
