package com.amar.vault.retrieval

import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem

/**
 * The only component that touches Room for retrieval. It owns hydration and
 * metadata-constraint queries — NO fusion, scoring, or planning. All lookups are
 * batched so the service never issues per-item queries.
 */
interface SearchRepository {
    suspend fun getByIds(ids: List<String>): List<VaultItem>
    suspend fun allItemsSnapshot(): List<VaultItem>

    suspend fun inSnapshotOrder(ids: List<String>): List<VaultItem> = getByIds(ids)

    /** Item ids whose DATE metadata falls in [startMs, endMs] (inclusive). */
    suspend fun dateRangeItemIds(startMs: Long, endMs: Long): List<String>

    /** Item ids that carry at least one DATE row — used to compute the "no-date" fallback set. */
    suspend fun itemIdsHavingDate(): Set<String>

    suspend fun factRows(ids: List<String>, amounts: List<Double>): List<Triple<String, String, String>> = emptyList()

    suspend fun amountBetweenIds(low: Double, high: Double): List<String> = amountGreaterThanIds(low - 0.001)

    suspend fun undatedItemIdsInRange(startMs: Long, endMs: Long): List<String> {
        val haveDate = itemIdsHavingDate()
        return allItemsSnapshot().filter { it.id !in haveDate && it.timestamp in startMs..endMs }.map { it.id }
    }

    suspend fun amountGreaterThanIds(value: Double): List<String>

    suspend fun itemIdsByTypeValue(type: String, value: String): List<String>

    /**
     * Batched effective-date lookup for a bounded set of items (temporal re-rank).
     * Preserves the prior semantics: first DATE metadata value, else the item timestamp.
     * Replaces the per-item query that ran inside the top-100 re-rank loop.
     */
    suspend fun effectiveDates(items: List<VaultItem>): Map<String, Long>
}

class VaultSearchRepository(private val db: VaultDatabase) : SearchRepository {

    private val vaultDao get() = db.vaultDao()
    private val metaDao get() = db.vaultMetadataDao()

    override suspend fun getByIds(ids: List<String>): List<VaultItem> =
        if (ids.isEmpty()) emptyList() else vaultDao.getByIds(ids)

    override suspend fun allItemsSnapshot(): List<VaultItem> = vaultDao.getAll()

    override suspend fun inSnapshotOrder(ids: List<String>): List<VaultItem> =
        ids.distinct().chunked(900).flatMap { vaultDao.getByIdsInSnapshotOrder(it) }
            .sortedWith(compareByDescending { it.timestamp })

    override suspend fun dateRangeItemIds(startMs: Long, endMs: Long): List<String> =
        metaDao.findItemsByTypeAndTimestampRange("DATE", startMs, endMs)

    override suspend fun undatedItemIdsInRange(startMs: Long, endMs: Long): List<String> =
        vaultDao.undatedIdsInRange(startMs, endMs)

    override suspend fun amountBetweenIds(low: Double, high: Double): List<String> =
        vaultDao.amountBetweenIds(low, high)

    override suspend fun factRows(ids: List<String>, amounts: List<Double>): List<Triple<String, String, String>> {
        val facts = db.factDao()
        val out = ArrayList<Triple<String, String, String>>()
        for (id in ids) facts.textEquals(com.amar.vault.vectors.FactKind.ID_NUMBER, id).forEach { out += Triple(it.rowId, it.kind, it.valueText) }
        for (a in amounts) facts.numbersBetween(com.amar.vault.vectors.FactKind.AMOUNT, a - 0.5, a + 0.5).forEach { out += Triple(it.rowId, it.kind, it.valueText) }
        return out.distinctBy { it.first to it.second }
    }

    override suspend fun itemIdsHavingDate(): Set<String> =
        metaDao.getItemIdsByType("DATE").toHashSet()

    override suspend fun amountGreaterThanIds(value: Double): List<String> =
        metaDao.findItemsByTypeAndNumericGreaterThan("AMOUNT", value)

    override suspend fun itemIdsByTypeValue(type: String, value: String): List<String> =
        metaDao.getByTypeAndValue(type, value).map { it.vaultItemId }

    override suspend fun effectiveDates(items: List<VaultItem>): Map<String, Long> {
        if (items.isEmpty()) return emptyMap()
        val ids = items.map { it.id }
        val firstDateByItem = metaDao.getByItemIds(ids)
            .asSequence()
            .filter { it.type == "DATE" }
            .groupBy { it.vaultItemId }
            .mapValues { (_, rows) -> rows.first().value.toLongOrNull() }
        return items.associate { item ->
            item.id to (firstDateByItem[item.id] ?: item.timestamp)
        }
    }
}
