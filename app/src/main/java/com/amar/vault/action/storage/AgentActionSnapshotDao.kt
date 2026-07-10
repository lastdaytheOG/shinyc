package com.amar.vault.action.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentActionSnapshotDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(snapshots: List<AgentActionSnapshot>)

    // ACTIVE FEED QUERY PROTECTION: Strictly limits UI rendering to CONFIRMED_ACTION + ACTIVE
    @Query("SELECT * FROM agent_action_snapshot WHERE actionState = 'CONFIRMED_ACTION' AND userState = 'ACTIVE' ORDER BY confidence DESC")
    suspend fun getActiveConfirmedActions(): List<AgentActionSnapshot>

    @Query("SELECT * FROM agent_action_snapshot WHERE actionState = 'INTERNAL_CANDIDATE'")
    suspend fun getInternalCandidates(): List<AgentActionSnapshot>

    @Query("UPDATE agent_action_snapshot SET actionState = 'STALE' WHERE actionId IN (:actionIds)")
    suspend fun markStale(actionIds: List<String>)

    @Query("UPDATE agent_action_snapshot SET actionState = 'CONFIRMED_ACTION', lastEvaluatedAt = :evaluatedAt WHERE actionId IN (:actionIds)")
    suspend fun confirmCandidates(actionIds: List<String>, evaluatedAt: Long)
    
    @Query("UPDATE agent_action_snapshot SET userState = :newState WHERE actionId = :actionId")
    suspend fun updateUserState(actionId: String, newState: String)

    // EXPIRATION WORKER QUERIES
    @Query("SELECT * FROM agent_action_snapshot WHERE actionState = 'CONFIRMED_ACTION' AND expiresAt IS NOT NULL")
    suspend fun getExpirableActions(): List<AgentActionSnapshot>

    @Query("UPDATE agent_action_snapshot SET actionState = 'ARCHIVED' WHERE actionId IN (:actionIds)")
    suspend fun archiveActions(actionIds: List<String>)

    @Query("UPDATE agent_action_snapshot SET lastEvaluatedAt = :evaluatedAt WHERE actionId IN (:actionIds)")
    suspend fun updateLastEvaluated(actionIds: List<String>, evaluatedAt: Long)
}
