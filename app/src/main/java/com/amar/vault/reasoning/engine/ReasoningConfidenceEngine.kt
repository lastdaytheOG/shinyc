package com.amar.vault.reasoning.engine

import com.amar.vault.reasoning.model.ReasoningSnapshotState
import com.amar.vault.reasoning.model.ReasoningState

object ReasoningConfidenceEngine {

    const val CONFIRMED_THRESHOLD = 0.95f
    const val MIN_REASONING_CONFIRMATION_BUILDS = 2

    fun evaluateConfidence(
        memoryConfidenceAverage: Float,
        evidenceCompleteness: Float,
        supportingMemoryCount: Int,
        minimumRequired: Int
    ): Float {
        if (supportingMemoryCount < minimumRequired) return 0.0f
        if (evidenceCompleteness < 1.0f) return 0.0f

        var score = memoryConfidenceAverage
        if (supportingMemoryCount > minimumRequired * 2) {
            score += 0.02f
        }
        return score.coerceIn(0.0f, 1.0f)
    }

    /**
     * Solves the Stability vs Confidence conflict.
     * Returns a Pair: (Business State, Snapshot Lifecycle State)
     */
    fun determineStates(confidence: Float, successfulBuildCount: Int): Pair<ReasoningState?, ReasoningSnapshotState> {
        if (confidence < CONFIRMED_THRESHOLD) {
            return Pair(null, ReasoningSnapshotState.FAILED) // DISCARD
        }

        return if (successfulBuildCount >= MIN_REASONING_CONFIRMATION_BUILDS) {
            // Confidence >= 0.95 AND MIN_BUILDS satisfied -> CONFIRMED/FRESH
            Pair(ReasoningState.CONFIRMED, ReasoningSnapshotState.FRESH)
        } else {
            // Confidence >= 0.95 BUT MIN_BUILDS not satisfied -> INTERNAL_CANDIDATE
            // Business state is not assigned yet.
            Pair(null, ReasoningSnapshotState.INTERNAL_CANDIDATE)
        }
    }
}
