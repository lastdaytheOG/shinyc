package com.amar.vault.vectors

import com.amar.vault.VaultDatabase
import java.util.Locale

class AliasMemory(private val db: VaultDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    data class Search(val query: String, val at: Long, val opened: Boolean)

    @Volatile private var last: Search? = null

    fun searched(query: String) {
        val q = norm(query)
        if (q.isEmpty()) return
        val previous = last
        if (previous != null && previous.query == q) return
        last = Search(q, clock(), opened = false)
        earlier = previous
    }

    @Volatile private var earlier: Search? = null

    suspend fun opened(query: String, itemId: String, verdict: String) {
        val q = norm(query)
        if (q.isEmpty()) return
        val now = clock()
        db.openDao().insert(Open(queryNorm = q, itemId = itemId, verdict = verdict, at = now))
        db.openDao().deleteBefore(now - OPENS_KEPT_MS)
        val before = earlier
        if (before != null && !before.opened && now - before.at <= WINDOW_MS && before.query != q) {
            val from = words(before.query)
            val to = words(q).filter { it !in from }
            if (from.isNotEmpty() && to.isNotEmpty() && from.size <= 4) {
                val term = from.joinToString(" ")
                val standsFor = to.joinToString(" ")
                val existing = db.aliasDao().forTerms(listOf(term)).firstOrNull { it.standsFor == standsFor }
                val weight = minOf(MAX_WEIGHT, (existing?.weight ?: 0f) + 0.5f)
                db.aliasDao().upsert(Alias(term, standsFor, weight, now))
            }
        }
        last = last?.copy(opened = true)
        earlier = null
    }

    suspend fun expand(query: String): List<String> {
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val now = clock()
        val terms = (listOf(q) + words(q)).distinct()
        return db.aliasDao().forTerms(terms)
            .filter { now - it.learnedAt <= ALIAS_KEPT_MS && it.weight >= MIN_WEIGHT }
            .sortedByDescending { it.weight }
            .take(MAX_EXPANSIONS)
            .map { it.standsFor }
    }

    suspend fun openedBefore(query: String): Map<String, Float> {
        val q = norm(query)
        if (q.isEmpty()) return emptyMap()
        val now = clock()
        return db.openDao().forQuery(q, now - OPENS_KEPT_MS)
            .groupBy { it.itemId }
            .mapValues { (_, opens) -> minOf(MAX_OPEN_WEIGHT, opens.sumOf { decay(now - it.at) }.toFloat()) }
    }

    private fun decay(ageMs: Long): Double = Math.pow(0.5, ageMs.toDouble() / HALF_LIFE_MS)

    companion object {
        const val WINDOW_MS = 60_000L
        const val OPENS_KEPT_MS = 90L * 24 * 3_600_000
        const val ALIAS_KEPT_MS = 180L * 24 * 3_600_000
        const val HALF_LIFE_MS = 30.0 * 24 * 3_600_000
        const val MAX_WEIGHT = 2f
        const val MIN_WEIGHT = 0.5f
        const val MAX_OPEN_WEIGHT = 2f
        const val MAX_EXPANSIONS = 3

        private val SMALL = setOf("a", "an", "the", "of", "in", "on", "for", "my", "ka", "ki", "ke", "का", "की", "के", "me", "mein")

        fun norm(query: String): String = query.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

        fun words(query: String): List<String> =
            norm(query).split(' ').filter { it.length >= 2 && it !in SMALL }.distinct()
    }
}
