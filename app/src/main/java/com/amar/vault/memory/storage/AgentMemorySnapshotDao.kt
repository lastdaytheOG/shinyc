package com.amar.vault.memory.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentMemorySnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<AgentMemorySnapshot>)

    // Agent Memory Firewall explicitly coded into the retrieval logic.
    // D.6 Reasoning Layer can only fetch CONFIRMED states.
    @Query("SELECT * FROM agent_memory_snapshot WHERE memoryState = 'CONFIRMED' ORDER BY confidence DESC")
    suspend fun getConfirmedMemories(): List<AgentMemorySnapshot>

    @Query("SELECT * FROM agent_memory_snapshot WHERE memoryId = :id")
    suspend fun getMemoryById(id: String): AgentMemorySnapshot?

    @Query("UPDATE agent_memory_snapshot SET memoryState = 'STALE' WHERE memoryId IN (:memoryIds)")
    suspend fun markMemoriesStale(memoryIds: List<String>)

    @Query("UPDATE agent_memory_snapshot SET memoryState = 'ARCHIVED' WHERE memoryId IN (:memoryIds)")
    suspend fun archiveMemories(memoryIds: List<String>)
}
