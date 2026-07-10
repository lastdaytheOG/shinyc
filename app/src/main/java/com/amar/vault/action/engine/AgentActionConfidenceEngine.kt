package com.amar.vault.action.engine

import com.amar.vault.action.model.ActionState

object AgentActionConfidenceEngine {

    const val CONFIRMED_ACTION_THRESHOLD = 0.99f
    const val MIN_ACTION_CONFIRMATION_BUILDS = 2

    fun evaluateConfidence(
        reasoningConfidenceAverage: Float,
        evidenceCompleteness: Float,
        supportingMemoryCount: Int
    ): Float {
        // Hard fail if minimum memory evidence isn't met (stricter than reasoning layer)
        if (supportingMemoryCount < 3) return 0.0f
        
        // Hard fail if evidence isn't totally complete
        if (evidenceCompleteness < 1.0f) return 0.0f

        return reasoningConfidenceAverage
    }

    /**
     * Determines the state based on the >0.99 threshold and the 2-build stability rule.
     */
    fun determineActionState(confidence: Float, successfulBuildCount: Int): ActionState {
        if (confidence < CONFIRMED_ACTION_THRESHOLD) {
            return ActionState.FAILED // Instantly Discard.
        }

        return if (successfulBuildCount >= MIN_ACTION_CONFIRMATION_BUILDS) {
            ActionState.CONFIRMED_ACTION
        } else {
            ActionState.INTERNAL_CANDIDATE
        }
    }
}
