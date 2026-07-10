package com.amar.vault.timeline.engine

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amar.vault.timeline.model.TimelineSnapshot

@Dao
interface TimelineSnapshotDao {
    @Query("SELECT * FROM timeline_snapshot WHERE state = 'FRESH' AND scope = :scope ORDER BY generatedAt DESC LIMIT 1")
    suspend fun getLatestFreshTimeline(scope: String = "GLOBAL"): TimelineSnapshot?

    @Query("SELECT * FROM timeline_snapshot WHERE timelineId = :timelineId LIMIT 1")
    suspend fun getTimelineById(timelineId: String): TimelineSnapshot?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snapshot: TimelineSnapshot)
    
    @Query("UPDATE timeline_snapshot SET state = 'FAILED' WHERE timelineId = :timelineId")
    suspend fun markFailed(timelineId: String)
}
