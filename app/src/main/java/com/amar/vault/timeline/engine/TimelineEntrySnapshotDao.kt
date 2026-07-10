package com.amar.vault.timeline.engine

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amar.vault.timeline.model.TimelineEntrySnapshot

@Dao
interface TimelineEntrySnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TimelineEntrySnapshot>)

    @Query("SELECT * FROM timeline_entry_snapshot WHERE timelineId = :timelineId ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedEntries(timelineId: String, limit: Int = 50, offset: Int = 0): List<TimelineEntrySnapshot>

    @Query("SELECT * FROM timeline_entry_snapshot WHERE timelineId = :timelineId AND timestamp IS NULL")
    suspend fun getUnknownDateEntries(timelineId: String): List<TimelineEntrySnapshot>

    @Query("DELETE FROM timeline_entry_snapshot WHERE timelineId = :timelineId")
    suspend fun deleteByTimelineId(timelineId: String)
}
