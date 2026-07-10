package com.amar.vault.reasoning.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ReasoningSnapshotDao {
    
    // IGNORE conflict strategy elegantly fulfills the "Abort Insert, Keep Existing Artifact" rule.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(snapshots: List<ReasoningSnapshot>)

    // Ultimate Agent Firewall: D.7+ Actions can ONLY read CONFIRMED AND FRESH reasoning.
    @Query("SELECT * FROM reasoning_snapshot WHERE reasoningState = 'CONFIRMED' AND snapshotState = 'FRESH' ORDER BY confidence DESC")
    suspend fun getConfirmedFreshReasoning(): List<ReasoningSnapshot>

    @Query("SELECT * FROM reasoning_snapshot WHERE snapshotState = 'INTERNAL_CANDIDATE'")
    suspend fun getInternalCandidates(): List<ReasoningSnapshot>

    @Query("UPDATE reasoning_snapshot SET snapshotState = 'STALE' WHERE reasoningId IN (:reasoningIds)")
    suspend fun markStale(reasoningIds: List<String>)

    @Query("UPDATE reasoning_snapshot SET snapshotState = 'FAILED' WHERE reasoningId IN (:reasoningIds)")
    suspend fun markFailed(reasoningIds: List<String>)
    
    @Query("UPDATE reasoning_snapshot SET snapshotState = 'FRESH', reasoningState = 'CONFIRMED' WHERE reasoningId IN (:reasoningIds)")
    suspend fun confirmCandidates(reasoningIds: List<String>)
}
