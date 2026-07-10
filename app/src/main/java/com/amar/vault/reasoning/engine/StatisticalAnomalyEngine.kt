package com.amar.vault.reasoning.engine

import kotlin.math.pow
import kotlin.math.sqrt

object StatisticalAnomalyEngine {

    /**
     * Calculates the Z-Score of the current value against a baseline array.
     * Z = (X - μ) / σ
     */
    fun calculateZScore(currentValue: Double, baselineHistory: List<Double>): Double {
        if (baselineHistory.isEmpty()) return 0.0
        val mean = baselineHistory.average()
        val variance = baselineHistory.map { (it - mean).pow(2) }.average()
        val standardDeviation = sqrt(variance)

        if (standardDeviation == 0.0) return 0.0 // Avoid divide by zero
        
        return (currentValue - mean) / standardDeviation
    }

    /**
     * Calculates simple percentage change.
     */
    fun calculatePercentChange(currentValue: Double, baselineAverage: Double): Double {
        if (baselineAverage == 0.0) return if (currentValue > 0) 100.0 else 0.0
        return ((currentValue - baselineAverage) / baselineAverage) * 100.0
    }

    /**
     * Detects if the Z-Score breaches the strict anomaly threshold.
     */
    fun isAnomaly(zScore: Double, threshold: Double = 2.0): Boolean {
        return kotlin.math.abs(zScore) >= threshold
    }
}
