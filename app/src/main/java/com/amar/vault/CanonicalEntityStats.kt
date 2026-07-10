package com.amar.vault

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Entity(tableName = "canonical_entity_stats")
data class CanonicalEntityStats(
    @PrimaryKey
    val canonicalId: String,
    val aliasCount: Int = 0,
    val documentCount: Int = 0,
    val averageConfidence: Float = 0f,
    val mergeFrequency: Int = 0
)

@Dao
interface CanonicalEntityStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: CanonicalEntityStats)

    @Query("SELECT * FROM canonical_entity_stats WHERE canonicalId = :id")
    suspend fun getStats(id: String): CanonicalEntityStats?

    @Query("UPDATE canonical_entity_stats SET documentCount = documentCount + 1, mergeFrequency = mergeFrequency + 1, averageConfidence = ((averageConfidence * (documentCount - 1)) + :confidence) / documentCount WHERE canonicalId = :id")
    suspend fun recordResolution(id: String, confidence: Float)
}
