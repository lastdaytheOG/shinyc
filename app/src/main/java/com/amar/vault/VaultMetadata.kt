package com.amar.vault

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "vault_metadata",
    foreignKeys = [
        ForeignKey(
            entity = VaultItem::class,
            parentColumns = ["id"],
            childColumns = ["vaultItemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("vaultItemId", "type"),
        Index("type", "value"),
        Index("type", "numericValue"),
        Index("type", "timestampValue")
    ]
)
data class VaultMetadata(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vaultItemId: String,
    val type: String,     
    val value: String,    
    val numericValue: Double? = null,
    val timestampValue: Long? = null,
    val confidence: Float,
    val source: String,             
    val extractionVersion: String   
)
data class EntityAggregationResult(
    val amountCount: Int,
    val totalValue: Double,
    val averageAmountConfidence: Float,
    @ColumnInfo(name = "evidenceIds") val rawEvidenceIds: String? = null
) {
    val evidenceIds: List<String>
        get() = rawEvidenceIds?.split(",")?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
}

@Dao
interface VaultMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: VaultMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metadataList: List<VaultMetadata>)

    @Query("SELECT * FROM vault_metadata WHERE vaultItemId = :itemId")
    suspend fun getByItemId(itemId: String): List<VaultMetadata>

    /**
     * Batched variant of [getByItemId] — fetches metadata rows for many items in a
     * single query. Used by the Saved feed to avoid an N+1 query storm when building
     * rich cards for large lists (10k+ items). Caller groups the result by vaultItemId.
     */
    @Query("SELECT * FROM vault_metadata WHERE vaultItemId IN (:itemIds)")
    suspend fun getByItemIds(itemIds: List<String>): List<VaultMetadata>

    @Query("SELECT * FROM vault_metadata WHERE vaultItemId = :itemId AND type = :type")
    suspend fun getByItemIdAndType(itemId: String, type: String): List<VaultMetadata>

    @Query("SELECT * FROM vault_metadata WHERE type = :type AND value = :value")
    suspend fun getByTypeAndValue(type: String, value: String): List<VaultMetadata>

    /** Batched: all item ids that have at least one row of [type]. Replaces per-item
     *  existence checks in the strict-DATE fallback (kills an N+1 storm). */
    @Query("SELECT DISTINCT vaultItemId FROM vault_metadata WHERE type = :type")
    suspend fun getItemIdsByType(type: String): List<String>

    @Query("DELETE FROM vault_metadata WHERE vaultItemId = :itemId")
    suspend fun deleteByItemId(itemId: String)

    /** A picture's SOURCE_TYPE row repeats its type; it is kept the same when the type changes. */
    @Query("UPDATE vault_metadata SET value = :value WHERE vaultItemId = :itemId AND type = 'SOURCE_TYPE'")
    suspend fun setSourceType(itemId: String, value: String)

    @Query("SELECT vaultItemId FROM vault_metadata WHERE type = :type AND timestampValue BETWEEN :startValue AND :endValue")
    suspend fun findItemsByTypeAndTimestampRange(type: String, startValue: Long, endValue: Long): List<String>
    
    @Query("SELECT vaultItemId FROM vault_metadata WHERE type = :type AND numericValue BETWEEN :startValue AND :endValue")
    suspend fun findItemsByTypeAndNumericRange(type: String, startValue: Double, endValue: Double): List<String>
    
    @Query("SELECT vaultItemId FROM vault_metadata WHERE type = :type AND numericValue > :value")
    suspend fun findItemsByTypeAndNumericGreaterThan(type: String, value: Double): List<String>

    @Query("""
        SELECT 
            COUNT(DISTINCT m1.vaultItemId) as amountCount, 
            SUM(m1.numericValue) as totalValue, 
            AVG(m1.confidence) as averageAmountConfidence,
            GROUP_CONCAT(DISTINCT m1.vaultItemId) as evidenceIds
        FROM vault_metadata m1
        INNER JOIN vault_metadata m2 ON m1.vaultItemId = m2.vaultItemId
        WHERE m1.type = :aggregateType 
          AND m2.type = :entityType 
          AND m2.value = :canonicalId
    """)
    suspend fun getAggregationForCanonicalEntity(
        canonicalId: String, 
        entityType: String, 
        aggregateType: String
    ): EntityAggregationResult?

    @Query("""
        SELECT MAX(mDate.timestampValue)
        FROM vault_metadata mDate
        INNER JOIN vault_metadata mEntity ON mDate.vaultItemId = mEntity.vaultItemId
        WHERE mDate.type = 'DATE' AND mEntity.value = :canonicalId
    """)
    suspend fun getLastActionDate(canonicalId: String): Long?
}
