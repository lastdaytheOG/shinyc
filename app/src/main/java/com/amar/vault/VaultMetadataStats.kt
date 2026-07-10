package com.amar.vault

import androidx.room.*

@Entity(tableName = "vault_metadata_stats")
data class VaultMetadataStats(
    @PrimaryKey val type: String,
    val extractionCount: Int,
    val averageConfidence: Float,
    val extractionFailures: Int,
    val sourceDistributionJson: String
)

@Dao
interface VaultMetadataStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: VaultMetadataStats)

    @Query("SELECT * FROM vault_metadata_stats")
    suspend fun getAllStats(): List<VaultMetadataStats>
    
    @Query("SELECT * FROM vault_metadata_stats WHERE type = :type")
    suspend fun getStatsByType(type: String): VaultMetadataStats?
}
