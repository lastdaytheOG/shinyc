package com.amar.vault

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IngestionSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: IngestionSession)

    @Query("SELECT * FROM ingestion_sessions WHERE id = :id")
    suspend fun getById(id: String): IngestionSession?
    
    @Query("UPDATE ingestion_sessions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: SessionStatus)
}

@Dao
interface IngestionAttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: IngestionAttachment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<IngestionAttachment>)

    @Query("SELECT * FROM ingestion_attachments WHERE sessionId = :sessionId")
    suspend fun getAttachmentsForSession(sessionId: String): List<IngestionAttachment>
    
    @Query("UPDATE ingestion_attachments SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: AttachmentStatus)
}
