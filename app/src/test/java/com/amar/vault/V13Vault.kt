package com.amar.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * A vault at database version 13, as it stands on a device: made with the statements Room
 * itself created it with (copied from a real version-13 database's `sqlite_master`), so a
 * migration is tried against what is actually there rather than against today's entities.
 */
internal object V13Vault {

    /** Room's identity hash for version 13, from the same database. */
    private const val IDENTITY_HASH = "28bfc7e4d93de15b17f689098813c4c6"

    private val SCHEMA = listOf(
        "CREATE TABLE `vault_items` (`id` TEXT NOT NULL, `uri` TEXT NOT NULL, `ocrText` TEXT NOT NULL, `lang` TEXT NOT NULL, `itemType` TEXT NOT NULL, `pageNum` INTEGER NOT NULL, `sourceFile` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `pHash` INTEGER NOT NULL, `tags` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `sourceApp` TEXT, `sharedAt` INTEGER, `originalUri` TEXT, `title` TEXT, `mimeType` TEXT, `parentDocumentId` TEXT, `chunkIndex` INTEGER NOT NULL DEFAULT 0, `totalChunks` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`id`))",
        "CREATE INDEX `index_vault_items_contentHash` ON `vault_items` (`contentHash`)",
        "CREATE VIRTUAL TABLE `vault_fts` USING FTS4(`ocrText` TEXT NOT NULL, content=`vault_items`)",
        "CREATE TRIGGER room_fts_content_sync_vault_fts_BEFORE_UPDATE BEFORE UPDATE ON `vault_items` BEGIN DELETE FROM `vault_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER room_fts_content_sync_vault_fts_BEFORE_DELETE BEFORE DELETE ON `vault_items` BEGIN DELETE FROM `vault_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER room_fts_content_sync_vault_fts_AFTER_UPDATE AFTER UPDATE ON `vault_items` BEGIN INSERT INTO `vault_fts`(`docid`, `ocrText`) VALUES (NEW.`rowid`, NEW.`ocrText`); END",
        "CREATE TRIGGER room_fts_content_sync_vault_fts_AFTER_INSERT AFTER INSERT ON `vault_items` BEGIN INSERT INTO `vault_fts`(`docid`, `ocrText`) VALUES (NEW.`rowid`, NEW.`ocrText`); END",
        "CREATE TABLE `vault_metadata` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `vaultItemId` TEXT NOT NULL, `type` TEXT NOT NULL, `value` TEXT NOT NULL, `numericValue` REAL, `timestampValue` INTEGER, `confidence` REAL NOT NULL, `source` TEXT NOT NULL, `extractionVersion` TEXT NOT NULL, FOREIGN KEY(`vaultItemId`) REFERENCES `vault_items`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX `index_vault_metadata_type_numericValue` ON `vault_metadata` (`type`, `numericValue`)",
        "CREATE INDEX `index_vault_metadata_type_timestampValue` ON `vault_metadata` (`type`, `timestampValue`)",
        "CREATE INDEX `index_vault_metadata_type_value` ON `vault_metadata` (`type`, `value`)",
        "CREATE INDEX `index_vault_metadata_vaultItemId_type` ON `vault_metadata` (`vaultItemId`, `type`)",
        "CREATE TABLE `vault_metadata_stats` (`type` TEXT NOT NULL, `extractionCount` INTEGER NOT NULL, `averageConfidence` REAL NOT NULL, `extractionFailures` INTEGER NOT NULL, `sourceDistributionJson` TEXT NOT NULL, PRIMARY KEY(`type`))",
        "CREATE TABLE `canonical_entity_stats` (`canonicalId` TEXT NOT NULL, `aliasCount` INTEGER NOT NULL, `documentCount` INTEGER NOT NULL, `averageConfidence` REAL NOT NULL, `mergeFrequency` INTEGER NOT NULL, PRIMARY KEY(`canonicalId`))",
        "CREATE TABLE `canonical_review_queue` (`rawText` TEXT NOT NULL, `suggestedCanonicalId` TEXT, `confidence` REAL NOT NULL, `frequency` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`rawText`))",
        "CREATE TABLE `vault_relationship` (`sourceId` TEXT NOT NULL, `targetId` TEXT NOT NULL, `relationshipType` TEXT NOT NULL, `confidence` REAL NOT NULL, `relationshipState` TEXT NOT NULL, `createdByRule` TEXT NOT NULL, `relationshipVersion` TEXT NOT NULL, `relationshipEvidenceJson` TEXT NOT NULL, PRIMARY KEY(`sourceId`, `targetId`, `relationshipType`))",
        "CREATE INDEX `index_vault_relationship_sourceId_relationshipType` ON `vault_relationship` (`sourceId`, `relationshipType`)",
        "CREATE INDEX `index_vault_relationship_targetId_relationshipType` ON `vault_relationship` (`targetId`, `relationshipType`)",
        "CREATE TABLE `event_snapshot` (`eventId` TEXT NOT NULL, `anchorType` TEXT NOT NULL, `anchorConfidence` REAL NOT NULL, `isEvidenceTruncated` INTEGER NOT NULL, `evidenceCount` INTEGER NOT NULL, `confirmedRelationshipCount` INTEGER NOT NULL, `locationEvidenceCount` INTEGER NOT NULL, `evidenceIdsJson` TEXT NOT NULL, `buildTimeMs` INTEGER NOT NULL, `retryCount` INTEGER NOT NULL, `lastFailureReason` TEXT, `eventBuildContextHash` TEXT NOT NULL, `relationshipVersion` TEXT NOT NULL, `classificationVersion` TEXT NOT NULL, `canonicalizationVersion` TEXT NOT NULL, `eventVersion` TEXT NOT NULL, `snapshotState` TEXT NOT NULL, PRIMARY KEY(`eventId`))",
        "CREATE INDEX `index_event_snapshot_anchorType` ON `event_snapshot` (`anchorType`)",
        "CREATE INDEX `index_event_snapshot_eventBuildContextHash` ON `event_snapshot` (`eventBuildContextHash`)",
        "CREATE INDEX `index_event_snapshot_snapshotState` ON `event_snapshot` (`snapshotState`)",
        "CREATE TABLE `timeline_snapshot` (`timelineId` TEXT NOT NULL, `scope` TEXT NOT NULL, `state` TEXT NOT NULL, `timelineVersion` TEXT NOT NULL, `generatedAt` INTEGER NOT NULL, `timelineContextHash` TEXT NOT NULL, PRIMARY KEY(`timelineId`))",
        "CREATE TABLE `timeline_entry_snapshot` (`entryId` TEXT NOT NULL, `timelineId` TEXT NOT NULL, `timestamp` INTEGER, `entryType` TEXT NOT NULL, `confidence` REAL NOT NULL, `state` TEXT NOT NULL, `title` TEXT NOT NULL, `evidenceIdsJson` TEXT NOT NULL, PRIMARY KEY(`entryId`))",
        "CREATE INDEX `index_timeline_entry_snapshot_entryType` ON `timeline_entry_snapshot` (`entryType`)",
        "CREATE INDEX `index_timeline_entry_snapshot_timelineId` ON `timeline_entry_snapshot` (`timelineId`)",
        "CREATE INDEX `index_timeline_entry_snapshot_timestamp` ON `timeline_entry_snapshot` (`timestamp`)",
        "CREATE TABLE `reasoning_snapshot` (`reasoningId` TEXT NOT NULL, `entityId` TEXT NOT NULL, `period` TEXT NOT NULL, `reasoningType` TEXT NOT NULL, `reasoningState` TEXT NOT NULL, `snapshotState` TEXT NOT NULL, `createdByRule` TEXT NOT NULL, `confidence` REAL NOT NULL, `occurrenceCount` INTEGER NOT NULL, `summary` TEXT NOT NULL, `supportingMemoryIdsJson` TEXT NOT NULL, `evidenceIdsJson` TEXT NOT NULL, `isTruncated` INTEGER NOT NULL, `reasoningVersion` TEXT NOT NULL, `reasoningContextHash` TEXT NOT NULL, PRIMARY KEY(`reasoningId`))",
        "CREATE UNIQUE INDEX `index_reasoning_snapshot_entityId_reasoningType_period` ON `reasoning_snapshot` (`entityId`, `reasoningType`, `period`)",
        "CREATE TABLE `agent_memory_snapshot` (`memoryId` TEXT NOT NULL, `memoryType` TEXT NOT NULL, `memoryState` TEXT NOT NULL, `confidence` REAL NOT NULL, `occurrenceCount` INTEGER NOT NULL, `supportingEventCount` INTEGER NOT NULL, `supportingRelationshipCount` INTEGER NOT NULL, `firstSeen` INTEGER NOT NULL, `lastSeen` INTEGER NOT NULL, `summary` TEXT NOT NULL, `evidenceIdsJson` TEXT NOT NULL, `memoryVersion` TEXT NOT NULL, `memoryContextHash` TEXT NOT NULL, PRIMARY KEY(`memoryId`))",
        "CREATE TABLE `agent_action_snapshot` (`actionId` TEXT NOT NULL, `entityId` TEXT NOT NULL, `period` TEXT NOT NULL, `actionType` TEXT NOT NULL, `actionState` TEXT NOT NULL, `userState` TEXT NOT NULL, `confidence` REAL NOT NULL, `summary` TEXT NOT NULL, `supportingReasoningIdsJson` TEXT NOT NULL, `evidenceIdsJson` TEXT NOT NULL, `createdByRule` TEXT NOT NULL, `occurrenceCount` INTEGER NOT NULL, `isTruncated` INTEGER NOT NULL, `lastTriggeredAt` INTEGER, `cooldownDays` INTEGER NOT NULL, `generatedAt` INTEGER NOT NULL, `lastEvaluatedAt` INTEGER NOT NULL, `expiresAt` INTEGER, `actionVersion` TEXT NOT NULL, `actionContextHash` TEXT NOT NULL, PRIMARY KEY(`actionId`))",
        "CREATE UNIQUE INDEX `index_agent_action_snapshot_entityId_actionType_period` ON `agent_action_snapshot` (`entityId`, `actionType`, `period`)",
        "CREATE TABLE `local_models` (`modelId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `fileName` TEXT NOT NULL, `downloadUrl` TEXT NOT NULL, `sha256` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, `requiredRamGb` REAL NOT NULL, `status` TEXT NOT NULL, `downloadProgress` INTEGER NOT NULL, `isEnabled` INTEGER NOT NULL, PRIMARY KEY(`modelId`))",
        "CREATE TABLE `chat_history` (`messageId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `sourcesJson` TEXT NOT NULL, `knowledgeCardJson` TEXT, `collectionCardJson` TEXT, PRIMARY KEY(`messageId`))",
        "CREATE INDEX `index_chat_history_sessionId_timestamp` ON `chat_history` (`sessionId`, `timestamp`)",
        "CREATE TABLE `stash_items` (`id` TEXT NOT NULL, `sessionId` TEXT, `vaultItemId` TEXT NOT NULL, `vaultType` TEXT NOT NULL, `category` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, `sourceApp` TEXT NOT NULL, `isFavorite` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `userNote` TEXT, `thumbnailPath` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`vaultItemId`) REFERENCES `vault_items`(`id`) ON UPDATE CASCADE ON DELETE CASCADE )",
        "CREATE INDEX `index_stash_items_sessionId` ON `stash_items` (`sessionId`)",
        "CREATE INDEX `index_stash_items_vaultItemId` ON `stash_items` (`vaultItemId`)",
        "CREATE UNIQUE INDEX `index_stash_items_vaultItemId_vaultType_category` ON `stash_items` (`vaultItemId`, `vaultType`, `category`)",
        "CREATE TABLE `ingestion_sessions` (`id` TEXT NOT NULL, `sourceType` TEXT NOT NULL, `action` TEXT NOT NULL, `type` TEXT, `sourcePackage` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `status` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE `ingestion_attachments` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `status` TEXT NOT NULL, `errorCode` TEXT NOT NULL, `attachmentType` TEXT NOT NULL, `originalUri` TEXT, `localPath` TEXT, `mimeType` TEXT NOT NULL, `filename` TEXT, `contentHash` TEXT, `width` INTEGER, `height` INTEGER, `duration` INTEGER, `fileSize` INTEGER, `pageCount` INTEGER, `domain` TEXT, `artist` TEXT, `album` TEXT, `latitude` REAL, `longitude` REAL, `thumbnailPath` TEXT, `previewTitle` TEXT, `rawExtrasJson` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`sessionId`) REFERENCES `ingestion_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX `index_ingestion_attachments_sessionId` ON `ingestion_attachments` (`sessionId`)",
        "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$IDENTITY_HASH')",
    )

    /** Makes an empty version-13 vault in the database file [name], replacing any there. */
    fun create(context: Context, name: String): SQLiteDatabase {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        SCHEMA.forEach(db::execSQL)
        db.version = 13
        return db
    }

    /** One row of `vault_items` as version 13 held it; `tags` was a column nothing wrote. */
    fun SQLiteDatabase.item(
        id: String, text: String, type: String, uri: String = "content://x/$id", sourceFile: String = "",
        timestamp: Long = 1_000L, parent: String? = null, chunkIndex: Int = 0, pageNum: Int = 0,
        contentHash: String = "", mimeType: String? = null, title: String? = null, sharedAt: Long? = null,
        totalChunks: Int = if (parent == null) 1 else 0,
    ) {
        insertOrThrow("vault_items", null, ContentValues().apply {
            put("id", id); put("uri", uri); put("ocrText", text); put("lang", "en"); put("itemType", type)
            put("pageNum", pageNum); put("sourceFile", sourceFile); put("timestamp", timestamp); put("pHash", 0L)
            put("tags", ""); put("contentHash", contentHash)
            put("mimeType", mimeType); put("title", title); put("sharedAt", sharedAt)
            put("parentDocumentId", parent); put("chunkIndex", chunkIndex); put("totalChunks", totalChunks)
        })
    }
}
