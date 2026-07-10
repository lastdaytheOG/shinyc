package com.amar.vault

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

enum class ReviewQueueStatus {
    ACTIVE,
    ARCHIVED,
    RESOLVED
}

@Entity(tableName = "canonical_review_queue")
data class CanonicalReviewQueue(
    @PrimaryKey
    val rawText: String,
    val suggestedCanonicalId: String?,
    val confidence: Float,
    val frequency: Int = 1,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val status: ReviewQueueStatus = ReviewQueueStatus.ACTIVE
)

@Dao
interface CanonicalReviewQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CanonicalReviewQueue)

    @Query("SELECT * FROM canonical_review_queue WHERE rawText = :rawText")
    suspend fun getByRawText(rawText: String): CanonicalReviewQueue?

    @Query("SELECT * FROM canonical_review_queue WHERE status = :status ORDER BY frequency DESC")
    suspend fun getByStatus(status: ReviewQueueStatus): List<CanonicalReviewQueue>

    @Query("UPDATE canonical_review_queue SET status = 'ARCHIVED' WHERE status = 'ACTIVE' AND lastSeen < :timestamp")
    suspend fun archiveStaleItems(timestamp: Long)
}
