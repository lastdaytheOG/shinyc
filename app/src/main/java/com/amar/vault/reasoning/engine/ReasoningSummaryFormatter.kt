package com.amar.vault.reasoning.engine

import com.amar.vault.reasoning.model.ReasoningType

object ReasoningSummaryFormatter {

    // 100% Deterministic string generation. No LLMs permitted.
    fun formatTrend(entityName: String, metric: String, startValue: Number, endValue: Number, startPeriod: String, endPeriod: String): String {
        val change = if (endValue.toDouble() > startValue.toDouble()) "increased" else "decreased"
        return "$entityName $metric $change from $startValue to $endValue between $startPeriod and $endPeriod."
    }

    fun formatAnomaly(entityName: String, metric: String, percentageChange: Double, baselinePeriod: String): String {
        val sign = if (percentageChange > 0) "+" else ""
        return "$entityName $metric anomaly detected: $sign${"%.1f".format(percentageChange)}% compared to $baselinePeriod average."
    }

    fun formatRecurringBehavior(entityName: String, actionName: String, occurrenceCount: Int): String {
        return "User $actionName $entityName $occurrenceCount times."
    }
}
