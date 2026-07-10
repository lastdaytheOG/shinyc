package com.amar.vault

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "vault_items",
    // Sprint P1 — contentHash backs the per-document duplicate check
    // (countByContentHash / deleteByContentHash); without the index each PDF
    // index pays a full table scan that grows with the vault.
    indices = [Index("contentHash")]
)
data class VaultItem(
    @PrimaryKey val id: String,
    val uri: String,
    val ocrText: String,
    val lang: String,

    val itemType: String,
    val pageNum: Int = 0,
    val sourceFile: String = "",
    val timestamp: Long,
    val pHash: Long = 0L,
    val tags: String = "",
    val contentHash: String = "",

    // Share-to-Vault Extensions
    val sourceApp: String? = null,
    val sharedAt: Long? = null,
    val originalUri: String? = null,
    val title: String? = null,
    val mimeType: String? = null,

    // Explicit chunk → parent ownership (Sprint 3B, Task 2). For a document chunk row,
    // parentDocumentId is the logical document id shared by all its sibling chunks;
    // for a standalone item (image, screenshot, shared link) it is NULL — the row is
    // its own root. Ownership must be read from these columns, never parsed from id.
    val parentDocumentId: String? = null,
    @ColumnInfo(defaultValue = "0") val chunkIndex: Int = 0,
    @ColumnInfo(defaultValue = "1") val totalChunks: Int = 1
)

@Fts4(contentEntity = VaultItem::class)
@Entity(tableName = "vault_fts")
data class VaultItemFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Int,
    val ocrText: String
)

data class VaultItemSearchData(
    val id: String,
    val ocrText: String,
    val tags: String,
    val itemType: String
)

@Dao
interface VaultDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VaultItem)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<VaultItem>)
    
    @Query("SELECT * FROM vault_items WHERE contentHash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): VaultItem?

    @Query("SELECT COUNT(*) FROM vault_items WHERE contentHash = :hash")
    suspend fun countByContentHash(hash: String): Int

    @Query("DELETE FROM vault_items WHERE contentHash = :hash")
    suspend fun deleteByContentHash(hash: String)

    @Query("""
        SELECT vault_items.* FROM vault_items
        JOIN vault_fts ON vault_items.rowid = vault_fts.rowid
        WHERE vault_fts MATCH :query
        LIMIT 50
    """)
    fun searchFts(query: String): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<VaultItem>
    @Query("DELETE FROM vault_items")
    suspend fun deleteAll()

    @Query("SELECT id, ocrText, tags, itemType FROM vault_items")
    suspend fun getAllSearchableData(): List<VaultItemSearchData>

    @Query("SELECT * FROM vault_items ORDER BY timestamp DESC")
    fun getAllItems(): Flow<List<VaultItem>>

    @Query("SELECT COUNT(*) FROM vault_items")
    suspend fun getCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM vault_items WHERE pHash = :hash LIMIT 1)")
    suspend fun hashExists(hash: Long): Boolean

    @Query("SELECT * FROM vault_items WHERE pHash = :hash LIMIT 1")
    suspend fun findByPHash(hash: Long): VaultItem?

    @Query("SELECT * FROM vault_items WHERE timestamp > :since ORDER BY timestamp DESC")
    suspend fun getItemsSince(since: Long): List<VaultItem>

    @Query("SELECT * FROM vault_items ORDER BY timestamp DESC")
    suspend fun getAll(): List<VaultItem>

    @Query("SELECT uri FROM vault_items")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM vault_items WHERE originalUri = :uri LIMIT 1")
    suspend fun findByOriginalUri(uri: String): VaultItem?

    @Query("SELECT * FROM vault_items WHERE sharedAt IS NOT NULL ORDER BY sharedAt DESC LIMIT 20")
    fun getRecentlyShared(): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE sharedAt IS NOT NULL ORDER BY sharedAt DESC")
    fun getAllSavedItems(): Flow<List<VaultItem>>

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Delete
    suspend fun delete(item: VaultItem)
}

@Entity(
    tableName = "vault_relationship",
    primaryKeys = ["sourceId", "targetId", "relationshipType"],
    indices = [
        Index("sourceId", "relationshipType"),
        Index("targetId", "relationshipType")
    ]
)
data class VaultRelationship(
    val sourceId: String,
    val targetId: String,
    val relationshipType: String,
    val confidence: Float,
    val relationshipState: String,
    val createdByRule: String,
    val relationshipVersion: String,
    val relationshipEvidenceJson: String
)

@Dao
interface VaultRelationshipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(relationships: List<VaultRelationship>)

    @Query("SELECT * FROM vault_relationship WHERE sourceId = :sourceId OR targetId = :sourceId")
    suspend fun getRelationshipsForDocument(sourceId: String): List<VaultRelationship>
    
    @Query("DELETE FROM vault_relationship WHERE relationshipVersion = :version")
    suspend fun deleteByVersion(version: String)
}

@Database(
    entities = [
        VaultItem::class,
        VaultItemFts::class,
        VaultMetadata::class, 
        VaultMetadataStats::class,
        CanonicalEntityStats::class,
        CanonicalReviewQueue::class,
        VaultRelationship::class,
        com.amar.vault.events.model.EventSnapshot::class,
        com.amar.vault.timeline.model.TimelineSnapshot::class,
        com.amar.vault.timeline.model.TimelineEntrySnapshot::class,
        com.amar.vault.reasoning.storage.ReasoningSnapshot::class,
        com.amar.vault.memory.storage.AgentMemorySnapshot::class,
        com.amar.vault.action.storage.AgentActionSnapshot::class,
        LocalModel::class,
        ChatMessageEntity::class,
        StashItem::class,
        IngestionSession::class,
        IngestionAttachment::class
    ], 
    version = 13,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun vaultMetadataDao(): VaultMetadataDao
    abstract fun vaultMetadataStatsDao(): VaultMetadataStatsDao
    abstract fun canonicalEntityStatsDao(): CanonicalEntityStatsDao
    abstract fun canonicalReviewQueueDao(): CanonicalReviewQueueDao
    abstract fun vaultRelationshipDao(): VaultRelationshipDao
    abstract fun stashItemDao(): StashItemDao
    abstract fun eventSnapshotDao(): com.amar.vault.events.engine.EventSnapshotDao
    abstract fun timelineSnapshotDao(): com.amar.vault.timeline.engine.TimelineSnapshotDao
    abstract fun timelineEntrySnapshotDao(): com.amar.vault.timeline.engine.TimelineEntrySnapshotDao
    abstract fun reasoningSnapshotDao(): com.amar.vault.reasoning.storage.ReasoningSnapshotDao
    abstract fun agentMemorySnapshotDao(): com.amar.vault.memory.storage.AgentMemorySnapshotDao
    abstract fun agentActionSnapshotDao(): com.amar.vault.action.storage.AgentActionSnapshotDao
    abstract fun localModelDao(): LocalModelDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun ingestionSessionDao(): IngestionSessionDao
    abstract fun ingestionAttachmentDao(): IngestionAttachmentDao
 
    companion object {
        @Volatile
        private var INSTANCE: VaultDatabase? = null
 
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `local_models` (
                        `modelId` TEXT NOT NULL, 
                        `displayName` TEXT NOT NULL, 
                        `fileName` TEXT NOT NULL, 
                        `downloadUrl` TEXT NOT NULL, 
                        `sha256` TEXT NOT NULL, 
                        `sizeBytes` INTEGER NOT NULL, 
                        `requiredRamGb` REAL NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `downloadProgress` INTEGER NOT NULL, 
                        `isEnabled` INTEGER NOT NULL, 
                        PRIMARY KEY(`modelId`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `chat_history` (
                        `messageId` TEXT NOT NULL, 
                        `sessionId` TEXT NOT NULL, 
                        `role` TEXT NOT NULL, 
                        `content` TEXT NOT NULL, 
                        `timestamp` INTEGER NOT NULL, 
                        `sourcesJson` TEXT NOT NULL, 
                        `knowledgeCardJson` TEXT, 
                        `collectionCardJson` TEXT, 
                        PRIMARY KEY(`messageId`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS `index_chat_history_sessionId_timestamp` 
                    ON `chat_history` (`sessionId`, `timestamp`)
                """.trimIndent())
            }
        }

        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `sourceApp` TEXT")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `sharedAt` INTEGER")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `originalUri` TEXT")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `title` TEXT")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `mimeType` TEXT")
            }
        }
 
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `stash_items` (
                        `id` TEXT NOT NULL,
                        `vaultItemId` TEXT NOT NULL,
                        `vaultType` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        `sourceApp` TEXT NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`vaultItemId`) REFERENCES `vault_items`(`id`) ON UPDATE CASCADE ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stash_items_vaultItemId` ON `stash_items` (`vaultItemId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_stash_items_vaultItemId_vaultType_category` ON `stash_items` (`vaultItemId`, `vaultType`, `category`)")
            }
        }

        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ingestion_sessions` (
                        `id` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `action` TEXT NOT NULL,
                        `type` TEXT,
                        `sourcePackage` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ingestion_attachments` (
                        `id` TEXT NOT NULL,
                        `sessionId` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorCode` TEXT NOT NULL,
                        `attachmentType` TEXT NOT NULL,
                        `originalUri` TEXT,
                        `localPath` TEXT,
                        `mimeType` TEXT NOT NULL,
                        `filename` TEXT,
                        `contentHash` TEXT,
                        `width` INTEGER,
                        `height` INTEGER,
                        `duration` INTEGER,
                        `fileSize` INTEGER,
                        `pageCount` INTEGER,
                        `domain` TEXT,
                        `artist` TEXT,
                        `album` TEXT,
                        `latitude` REAL,
                        `longitude` REAL,
                        `thumbnailPath` TEXT,
                        `previewTitle` TEXT,
                        `rawExtrasJson` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`sessionId`) REFERENCES `ingestion_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingestion_attachments_sessionId` ON `ingestion_attachments` (`sessionId`)")
                db.execSQL("ALTER TABLE `stash_items` ADD COLUMN `sessionId` TEXT DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stash_items_sessionId` ON `stash_items` (`sessionId`)")
            }
        }

        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stash_items` ADD COLUMN `userNote` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `stash_items` ADD COLUMN `thumbnailPath` TEXT DEFAULT NULL")
            }
        }

        /**
         * Sprint 3B, Task 2 — explicit chunk → parent ownership columns.
         *
         * The backfill below is the one sanctioned, final use of the historical
         * "${parentId}_chunk${index}" id convention: it converts the implicit
         * relationship into explicit columns at upgrade time. All code reads the
         * columns from here on. Rows without the marker (images, screenshots,
         * shared links) are standalone roots: parentDocumentId stays NULL,
         * chunkIndex 0, totalChunks 1 (the column defaults).
         */
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `parentDocumentId` TEXT")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `chunkIndex` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `totalChunks` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("""
                    UPDATE `vault_items` SET
                        `parentDocumentId` = substr(`id`, 1, instr(`id`, '_chunk') - 1),
                        `chunkIndex` = CAST(substr(`id`, instr(`id`, '_chunk') + 6) AS INTEGER)
                    WHERE instr(`id`, '_chunk') > 0
                """.trimIndent())
                db.execSQL("""
                    UPDATE `vault_items` SET `totalChunks` = (
                        SELECT COUNT(*) FROM `vault_items` v2
                        WHERE v2.`parentDocumentId` = `vault_items`.`parentDocumentId`
                    )
                    WHERE `parentDocumentId` IS NOT NULL
                """.trimIndent())
            }
        }

        /**
         * Sprint P1 — index on vault_items.contentHash. Name must match what Room
         * derives from the @Entity indices declaration (index_vault_items_contentHash)
         * or schema validation fails on open.
         */
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_contentHash` ON `vault_items` (`contentHash`)")
            }
        }

        fun get(context: Context): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    VaultDatabase::class.java,
                    "vault.db"
                )
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
