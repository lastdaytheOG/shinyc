package com.amar.vault.events.engine

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.amar.vault.events.model.EventSnapshot

@Dao
interface EventSnapshotDao {
    @Query("SELECT * FROM event_snapshot WHERE snapshotState = 'FRESH'")
    suspend fun getFreshEvents(): List<EventSnapshot>
    
    @Query("SELECT * FROM event_snapshot WHERE eventId = :id")
    suspend fun getEventById(id: String): EventSnapshot?
    
    @Query("UPDATE event_snapshot SET snapshotState = 'STALE' WHERE eventId = :id")
    suspend fun markStale(id: String)
    
    @Query("UPDATE event_snapshot SET snapshotState = 'FAILED', retryCount = retryCount + 1, lastFailureReason = :reason WHERE eventId = :id")
    suspend fun markFailed(id: String, reason: String)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: EventSnapshot)
}
