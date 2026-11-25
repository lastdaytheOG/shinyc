package com.amar.vault.retrieval

import com.amar.vault.VaultConfig
import com.amar.vault.VaultItem

object MeaningLane {

    suspend fun resolve(
        scan: MeaningScan,
        repository: SearchRepository,
        meaning: MeaningRetriever,
        only: ((VaultItem) -> Boolean)?,
        onePerDocument: Boolean,
        gate: MeaningGate = MeaningGate(),
    ): MeaningResult {
        if (scan.hits.isEmpty()) return MeaningResult(emptyList(), emptyList(), Verdict.NOTHING, scan.coverage, mean = scan.mean, deviation = scan.deviation)
        val rows = scan.hits.map { it.rowId }.distinct().chunked(900)
            .flatMap { repository.getByIds(it) }.associateBy { it.id }
        var matches = scan.hits.mapNotNull { h ->
            val row = rows[h.rowId] ?: return@mapNotNull null
            if (!meaning.isCurrent(row)) return@mapNotNull null
            if (only != null && !only(row)) return@mapNotNull null
            MeaningMatch(row, h.score, h.margin, scan.standing(h.score))
        }
        if (onePerDocument) matches = matches.distinctBy { it.item.parentDocumentId ?: it.item.id }
        val shown = gate.keep(matches, scan.scanned)
        return MeaningResult(shown, matches, gate.verdict(shown, scan.coverage), scan.coverage, mean = scan.mean, deviation = scan.deviation)
    }
}

class MeaningGate(
    private val limit: Int = VaultConfig.Retrieval.MEANING_SHOWN,
) {
    fun keep(matches: List<MeaningMatch>, @Suppress("UNUSED_PARAMETER") scanned: Int = Int.MAX_VALUE): List<MeaningMatch> {
        val passing = matches.filter {
            it.score >= VaultConfig.Retrieval.MEANING_FIXED_SCORE &&
                it.margin >= VaultConfig.Retrieval.MEANING_FIXED_MARGIN
        }
        return passing.sortedByDescending { it.score }.take(limit)
    }

    fun verdict(shown: List<MeaningMatch>, coverage: Float): Verdict {
        if (shown.isEmpty()) return if (coverage < 1f) Verdict.NOTHING_YET else Verdict.NOTHING
        return Verdict.POSSIBLE
    }
}

object Groups {

    private val ORDER = listOf(EvidenceKind.SAYS, EvidenceKind.NAME, EvidenceKind.TAG, EvidenceKind.NEAR_SPELLING,
        EvidenceKind.FACT, EvidenceKind.LOOKS, EvidenceKind.MEANING)

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + Math.exp(-x))

    fun keywordChance(wordsSaid: Int, words: Int, similarOnly: Boolean): Double {
        val c = VaultConfig.Retrieval
        val coverage = if (words > 0) wordsSaid.toDouble() / words else 0.0
        return sigmoid(c.SAYS_BIAS + c.SAYS_COVERAGE * (if (similarOnly) 0.0 else coverage))
    }

    fun looksChance(photos: List<PhotoHit>): Double {
        val c = VaultConfig.Retrieval
        return if (photos.isEmpty()) 0.0 else sigmoid(c.LOOKS_BIAS + c.LOOKS_MARGIN * photos[0].margin + c.LOOKS_SCORE * photos[0].score)
    }

    fun meaningChance(meaning: MeaningResult?): Double {
        val c = VaultConfig.Retrieval
        val top = meaning?.shown?.firstOrNull() ?: return 0.0
        return sigmoid(c.MEANING_BIAS + c.MEANING_STANDING * top.standing + c.MEANING_MARGIN * top.margin)
    }

    private fun verdict(chance: Double, sureAt: Double): Verdict = if (chance >= sureAt) Verdict.SURE else Verdict.POSSIBLE

    fun keywordVerdict(items: List<VaultItem>, wordsSaid: Int, words: Int, similarOnly: Boolean): Verdict =
        if (items.isEmpty()) Verdict.NOTHING
        else verdict(keywordChance(wordsSaid, words, similarOnly), VaultConfig.Retrieval.SAYS_SURE_AT)

    fun of(
        items: List<VaultItem>, wordsSaid: Int, words: Int, similarOnly: Boolean,
        meaning: MeaningResult?, photos: List<PhotoHit>, facts: FactResult? = null,
    ): Pair<List<ResultGroup>, Verdict> {
        val c = VaultConfig.Retrieval
        val scored = ArrayList<Pair<ResultGroup, Double>>()
        if (items.isNotEmpty()) {
            val p = keywordChance(wordsSaid, words, similarOnly)
            scored += ResultGroup(if (similarOnly) EvidenceKind.NEAR_SPELLING else EvidenceKind.SAYS, verdict(p, c.SAYS_SURE_AT),
                items.map { it.parentDocumentId ?: it.id }.distinct()) to p
        }
        if (facts != null && facts.matches.isNotEmpty()) {
            val p = if (facts.matches.first().exact) c.FACT_ID_CHANCE else c.FACT_AMOUNT_CHANCE
            scored += ResultGroup(EvidenceKind.FACT, verdict(p, c.FACT_SURE_AT),
                facts.matches.map { it.item.parentDocumentId ?: it.item.id }.distinct()) to p
        }
        if (photos.isNotEmpty()) {
            val p = looksChance(photos)
            scored += ResultGroup(EvidenceKind.LOOKS, verdict(p, c.LOOKS_SURE_AT), photos.map { it.item.id }) to p
        }
        if (meaning != null && meaning.shown.isNotEmpty()) {
            val p = meaningChance(meaning)
            scored += ResultGroup(EvidenceKind.MEANING, verdict(p, c.MEANING_SURE_AT),
                meaning.shown.map { it.item.parentDocumentId ?: it.item.id }) to p
        }
        val saysEveryWord = items.isNotEmpty() && !similarOnly && words > 0 && wordsSaid >= words
        val ordered = scored.sortedWith(compareBy<Pair<ResultGroup, Double>> { if (saysEveryWord && it.first.kind == EvidenceKind.SAYS) 0 else 1 }
            .thenByDescending { it.second }
            .thenBy { ORDER.indexOf(it.first.kind) }).map { it.first }
        val seen = HashSet<String>()
        val deduped = ordered.map { g -> g.copy(ids = g.ids.filter { seen.add(it) }) }.filter { it.ids.isNotEmpty() }
        val overall = when {
            deduped.any { it.verdict == Verdict.SURE } -> Verdict.SURE
            deduped.isNotEmpty() -> Verdict.POSSIBLE
            meaning != null && meaning.coverage < 1f -> Verdict.NOTHING_YET
            else -> Verdict.NOTHING
        }
        return deduped to overall
    }
}
