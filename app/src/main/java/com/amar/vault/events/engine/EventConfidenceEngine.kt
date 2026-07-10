package com.amar.vault.events.engine

import com.amar.vault.VaultRelationship

object EventConfidenceEngine {
    
    fun calculateConfidence(anchorConfidence: Float, evidence: List<VaultRelationship>): Float {
        // Simple logic for now, could be expanded later
        // It strictly doesn't mutate evidence as per requirements
        
        var score = anchorConfidence * 0.50f
        
        for (rel in evidence) {
            score += rel.confidence * 0.10f
        }
        
        // Cap confidence at 1.0f
        return score.coerceAtMost(1.0f)
    }
}
