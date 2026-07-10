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

    /** Item ids whose DATE metadata falls in [startMs, endMs] (inclusive). */
    suspend fun dateRangeItemIds(startMs: Long, endMs: Long): List<String>

    /** Item ids that carry at least one DATE row — used to compute the "no-date" fallback set. */
    suspend fun itemIdsHavingDate(): Set<String>

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

    override suspend fun dateRangeItemIds(startMs: Long, endMs: Long): List<String> =
        metaDao.findItemsByTypeAndTimestampRange("DATE", startMs, endMs)

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
