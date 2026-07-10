package com.amar.vault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<ChatMessageEntity>)

    @Query("SELECT * FROM chat_history WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_history WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionDirect(sessionId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_history")
    suspend fun clearHistory()
}
