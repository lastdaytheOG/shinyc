package com.amar.vault.memory.engine

import com.amar.vault.memory.model.MemoryType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MemorySummaryFormatter {
    
    private val monthYearFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

    fun formatSummary(
        type: MemoryType,
        entityName: String,
        occurrenceCount: Int,
        firstSeen: Long,
        lastSeen: Long
    ): String {
        val firstDate = monthYearFormat.format(Date(firstSeen))
        val lastDate = monthYearFormat.format(Date(lastSeen))
        val timeframe = if (firstDate == lastDate) firstDate else "$firstDate and $lastDate"

        return when (type) {
            MemoryType.RECURRING_PAYMENT -> "Paid $entityName $occurrenceCount times between $timeframe"
            MemoryType.RECURRING_PURCHASE -> "Purchased from $entityName $occurrenceCount times between $timeframe"
            MemoryType.RECURRING_TRAVEL -> "Traveled to $entityName $occurrenceCount times between $timeframe"
            MemoryType.LONG_TERM_ENTITY -> "Interacted with $entityName across $occurrenceCount months between $timeframe"
            MemoryType.RECURRING_DOCUMENT -> "Processed $occurrenceCount $entityName documents between $timeframe"
            MemoryType.IMPORTANT_EVENT -> "Important Event: $entityName occurred on $firstDate"
        }
    }
}
