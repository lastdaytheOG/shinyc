package com.amar.vault

import android.content.Context

object EvidenceValidator {
    
    suspend fun validateKnowledgeCard(card: KnowledgeCard, context: Context): Boolean {
        if (card.evidenceIds.isEmpty()) return false
        
        val db = VaultDatabase.get(context)
        val metaDao = db.vaultMetadataDao()
        
        var evidenceTotal = 0.0
        var evidenceCount = 0
        
        // Sum up the raw extracted evidence
        for (id in card.evidenceIds) {
            val amounts = metaDao.getByItemIdAndType(id, "AMOUNT")
            if (amounts.isNotEmpty()) {
                evidenceTotal += amounts.first().numericValue ?: 0.0
                evidenceCount++
            }
        }
        
        // Check if the displayed total exactly matches the mathematical evidence
        val isTotalMatch = Math.abs(evidenceTotal - card.totalValue) < 0.01
        
        return isTotalMatch
    }
}
