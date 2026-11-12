package com.amar.vault.retrieval

import com.amar.vault.AmountReading
import com.amar.vault.VaultItem
import java.util.Locale

data class FactMatch(val item: VaultItem, val kind: String, val value: String, val exact: Boolean)

data class FactResult(val matches: List<FactMatch>)

object FactsLane {

    private val ID = Regex("(?<![A-Za-z0-9])([A-Za-z]{0,4}\\d[A-Za-z0-9-]{4,24})(?![A-Za-z0-9])")
    private val NUMBER = Regex("(?<![\\d,.])(\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?:\\s*(lakh|lakhs|lac|crore|crores|cr|k|hazaar|हज़ार|लाख|करोड़))?(?![\\d,])")

    fun wants(query: String): Boolean = NUMBER.containsMatchIn(query)

    fun ids(query: String): List<String> = ID.findAll(query).map { it.groupValues[1].replace("-", "").uppercase(Locale.ROOT) }
        .filter { it.count(Char::isDigit) >= 4 }.distinct().toList()

    fun amounts(query: String): List<Double> = NUMBER.findAll(query).mapNotNull { m ->
        val digits = m.groupValues[1]
        val plain = digits.replace(",", "")
        if (m.groupValues[2].isEmpty() && plain.length > 9 && !plain.contains('.')) null
        else AmountReading.value(digits, m.groupValues[2].ifEmpty { null })?.takeIf { it >= 10 }
    }.distinct().toList()

    suspend fun find(query: String, repository: SearchRepository, only: ((VaultItem) -> Boolean)?, onePerDocument: Boolean): FactResult? {
        if (!wants(query)) return null
        val ids = ids(query)
        val amounts = amounts(query)
        if (ids.isEmpty() && amounts.isEmpty()) return null
        val hits = repository.factRows(ids, amounts)
        if (hits.isEmpty()) return FactResult(emptyList())
        val rows = repository.getByIds(hits.map { it.first }.distinct()).associateBy { it.id }
        var matches = hits.mapNotNull { (rowId, kind, value) ->
            val row = rows[rowId] ?: return@mapNotNull null
            if (only != null && !only(row)) return@mapNotNull null
            FactMatch(row, kind, value, kind == "ID_NUMBER")
        }.sortedByDescending { if (it.exact) 1 else 0 }
        if (onePerDocument) matches = matches.distinctBy { it.item.parentDocumentId ?: it.item.id }
        return FactResult(matches.take(20))
    }
}
