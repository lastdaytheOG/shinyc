package com.amar.vault

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "stash_items",
    foreignKeys = [
        ForeignKey(
            entity = VaultItem::class,
            parentColumns = ["id"],
            childColumns = ["vaultItemId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("vaultItemId"),
        Index(value = ["vaultItemId", "vaultType", "category"], unique = true)
    ]
)
data class StashItem(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val sessionId: String? = null,
    val vaultItemId: String,
    val vaultType: String = "SAVED", 
    val category: String, // Empty string for Uncategorized/Skip
    val savedAt: Long,
    val sourceApp: String,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val userNote: String? = null,
    val thumbnailPath: String? = null
)

data class StashItemWithVaultItem(
    val stashId: String,
    val sessionId: String?,
    val vaultItemId: String,
    val vaultType: String,
    val category: String,
    val savedAt: Long,
    val sourceApp: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val userNote: String?,
    val thumbnailPath: String?,
    
    // VaultItem fields needed for UI
    val uri: String,
    val ocrText: String,
    val itemType: String,
    val sourceFile: String,
    val timestamp: Long,
    val title: String?,
    val mimeType: String?
) {
    /** Reconstructs the persisted [StashItem] row (used for in-memory undo re-insert). */
    fun toStashItem(): StashItem = StashItem(
        id = stashId,
        sessionId = sessionId,
        vaultItemId = vaultItemId,
        vaultType = vaultType,
        category = category,
        savedAt = savedAt,
        sourceApp = sourceApp,
        isFavorite = isFavorite,
        createdAt = createdAt,
        userNote = userNote,
        thumbnailPath = thumbnailPath
    )

    fun toVaultItem(): VaultItem {
        return VaultItem(
            id = this.vaultItemId,
            uri = this.uri,
            ocrText = this.ocrText,
            lang = "eng",
            itemType = this.itemType,
            pageNum = 0,
            sourceFile = this.sourceFile,
            timestamp = this.timestamp,
            pHash = 0L,
            tags = this.category,
            contentHash = "",
            sourceApp = this.sourceApp,
            sharedAt = this.savedAt,
            originalUri = null,
            title = this.title,
            mimeType = this.mimeType
        )
    }
}

data class CategoryCount(
    val category: String,
    val count: Int
)

@Dao
interface StashItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: StashItem): Long

    @Query("UPDATE stash_items SET savedAt = :savedAt WHERE vaultItemId = :vaultItemId AND vaultType = :vaultType AND category = :category")
    suspend fun updateSavedAt(vaultItemId: String, vaultType: String, category: String, savedAt: Long)

    @androidx.room.Transaction
    suspend fun insertOrUpdate(item: StashItem) {
        val id = insertIgnore(item)
        if (id == -1L) {
            updateSavedAt(item.vaultItemId, item.vaultType, item.category, item.savedAt)
        }
    }

    @Query("""
        SELECT 
            s.id AS stashId,
            s.sessionId,
            s.vaultItemId,
            s.vaultType,
            s.category,
            s.savedAt,
            s.sourceApp,
            s.isFavorite,
            s.createdAt,
            s.userNote,
            s.thumbnailPath,
            v.uri,
            v.ocrText,
            v.itemType,
            v.sourceFile,
            v.timestamp,
            v.title,
            v.mimeType
        FROM stash_items s
        INNER JOIN vault_items v ON s.vaultItemId = v.id
        WHERE s.vaultType = :vaultType
        ORDER BY s.savedAt DESC
    """)
    fun getStashItemsByType(vaultType: String): Flow<List<StashItemWithVaultItem>>

    @Query("""
        SELECT 
            s.id AS stashId,
            s.sessionId,
            s.vaultItemId,
            s.vaultType,
            s.category,
            s.savedAt,
            s.sourceApp,
            s.isFavorite,
            s.createdAt,
            s.userNote,
            s.thumbnailPath,
            v.uri,
            v.ocrText,
            v.itemType,
            v.sourceFile,
            v.timestamp,
            v.title,
            v.mimeType
        FROM stash_items s
        INNER JOIN vault_items v ON s.vaultItemId = v.id
        WHERE s.vaultType = :vaultType AND s.category = :category
        ORDER BY s.savedAt DESC
    """)
    fun getStashItemsByTypeAndCategory(vaultType: String, category: String): Flow<List<StashItemWithVaultItem>>

    @Query("UPDATE stash_items SET isFavorite = :isFavorite WHERE id = :stashId")
    suspend fun updateFavorite(stashId: String, isFavorite: Boolean)

    @Query("UPDATE stash_items SET category = :category WHERE id = :stashId")
    suspend fun updateCategory(stashId: String, category: String)

    // ── Phase 3: bulk & management operations (additive; no schema change) ──────

    @Query("UPDATE stash_items SET category = :category WHERE id IN (:stashIds)")
    suspend fun bulkSetCategory(stashIds: List<String>, category: String)

    @Query("UPDATE stash_items SET isFavorite = :isFavorite WHERE id IN (:stashIds)")
    suspend fun bulkSetFavorite(stashIds: List<String>, isFavorite: Boolean)

    /** Archive/unarchive by flipping vaultType between "SAVED" and "ARCHIVED". */
    @Query("UPDATE stash_items SET vaultType = :vaultType WHERE id IN (:stashIds)")
    suspend fun bulkSetType(stashIds: List<String>, vaultType: String)

    @Query("DELETE FROM stash_items WHERE id IN (:stashIds)")
    suspend fun bulkDelete(stashIds: List<String>)

    /** Rename a category, or merge into an existing one, for all SAVED+ARCHIVED rows. */
    @Query("UPDATE stash_items SET category = :newCategory WHERE category = :oldCategory")
    suspend fun renameCategory(oldCategory: String, newCategory: String)

    /** Related attachments: other items captured in the same share session. */
    @Query("""
        SELECT
            s.id AS stashId, s.sessionId, s.vaultItemId, s.vaultType, s.category,
            s.savedAt, s.sourceApp, s.isFavorite, s.createdAt, s.userNote, s.thumbnailPath,
            v.uri, v.ocrText, v.itemType, v.sourceFile, v.timestamp, v.title, v.mimeType
        FROM stash_items s
        INNER JOIN vault_items v ON s.vaultItemId = v.id
        WHERE s.sessionId = :sessionId AND s.id != :excludeStashId
        ORDER BY s.savedAt DESC
    """)
    suspend fun getBySessionId(sessionId: String, excludeStashId: String): List<StashItemWithVaultItem>

    @Query("SELECT COUNT(*) FROM stash_items WHERE vaultType = 'ARCHIVED'")
    fun getArchivedCount(): Flow<Int>

    @Query("UPDATE stash_items SET userNote = :note WHERE id = :stashId")
    suspend fun updateNote(stashId: String, note: String)

    @Query("UPDATE stash_items SET thumbnailPath = :path WHERE id = :stashId")
    suspend fun updateThumbnailPath(stashId: String, path: String)

    @Query("SELECT * FROM stash_items WHERE vaultItemId = :vaultItemId LIMIT 1")
    suspend fun getByVaultItemId(vaultItemId: String): StashItem?

    @Query("DELETE FROM stash_items WHERE id = :stashId")
    suspend fun deleteById(stashId: String)

    @Query("SELECT DISTINCT category FROM stash_items WHERE category != ''")
    suspend fun getDistinctCategories(): List<String>

    @Query("SELECT category, COUNT(*) as count FROM stash_items WHERE vaultType = 'SAVED' GROUP BY category ORDER BY MAX(savedAt) DESC")
    fun getCategoriesWithCounts(): Flow<List<CategoryCount>>

    @Query("SELECT COUNT(*) FROM stash_items WHERE vaultType = 'SAVED'")
    fun getTotalSavedCount(): Flow<Int>
}
