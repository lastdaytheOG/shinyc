package com.amar.vault.memory.engine

import com.amar.vault.memory.model.MemoryState

object MemoryConfidenceEngine {

    const val CONFIRMED_THRESHOLD = 0.95f
    const val CANDIDATE_THRESHOLD = 0.80f

    fun evaluateConfidence(
        baseConfidence: Float,
        occurrenceCount: Int,
        minRequiredOccurrences: Int,
        evidenceCompleteness: Float
    ): Float {
        var score = baseConfidence

        // Boost for repeated high-volume occurrences
        if (occurrenceCount > minRequiredOccurrences * 2) {
            score += 0.05f
        }

        // Penalize for missing underlying evidence links
        if (evidenceCompleteness < 1.0f) {
            score -= (1.0f - evidenceCompleteness) * 0.5f
        }

        return score.coerceIn(0.0f, 1.0f)
    }

    fun determineState(confidence: Float): MemoryState {
        return when {
            confidence >= CONFIRMED_THRESHOLD -> MemoryState.CONFIRMED
            confidence >= CANDIDATE_THRESHOLD -> MemoryState.STALE // Candidates aren't strictly stored, but STALE maps to non-confirmed state logic for existing
            else -> MemoryState.ARCHIVED // DISCARD
        }
    }
}
