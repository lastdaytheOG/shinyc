package com.amar.vault

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 14 gives three things a place of their own. Nothing is deleted and no id changes;
 * `vault_items` is altered in place, never rebuilt.
 *
 *  1. Tags. They were glued onto each item's text as a last line in brackets
 *     ([FormerStoredText]). They move to the `tags` column, which was there and empty; what a
 *     picture's QR codes hold moves to the new `qrPayload`; `ocrText` keeps only what was read.
 *  2. Types. `itemType` was whatever the place that stored the item called it ("pdf", "PDF",
 *     "dev_manual", "YOUTUBE", …). Every row is rewritten to a name from [ItemType].
 *  3. Documents. A document was only its pieces, each repeating its name and address. Each
 *     now has one row in the new `documents` table ([VaultDocument]).
 */
internal class Migration13To14(private val onDone: (MigrationReport) -> Unit = {}) : Migration(13, 14) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `qrPayload` TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `documents` (`id` TEXT NOT NULL, `uri` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`itemType` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `pageCount` INTEGER, " +
                "`chunkCount` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_contentHash` ON `documents` (`contentHash`)")
        // A document's pieces are asked for by the document: without this, every such
        // question reads the whole table.
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_parentDocumentId` ON `vault_items` (`parentDocumentId`)")

        val report = rewriteItems(db)

        // Room drops the triggers that keep the full-text table in step with `vault_items` for
        // the length of a migration, so the texts rewritten above have not reached it.
        db.execSQL("INSERT INTO `vault_fts`(`vault_fts`) VALUES('rebuild')")

        // One row per document, from its pieces: how many there are and when the first was
        // stored; the name, address, type and hash are those of its first piece.
        db.execSQL(
            "INSERT OR REPLACE INTO `documents` (`id`, `uri`, `name`, `itemType`, `contentHash`, `pageCount`, `chunkCount`, `addedAt`) " +
                "SELECT g.`parentDocumentId`, f.`uri`, f.`sourceFile`, f.`itemType`, f.`contentHash`, NULL, g.`pieces`, g.`firstAt` " +
                "FROM (SELECT `parentDocumentId`, COUNT(*) AS `pieces`, MIN(`timestamp`) AS `firstAt`, MIN(`chunkIndex`) AS `firstPiece` " +
                "FROM `vault_items` WHERE `parentDocumentId` IS NOT NULL GROUP BY `parentDocumentId`) g " +
                "JOIN `vault_items` f ON f.`parentDocumentId` = g.`parentDocumentId` AND f.`chunkIndex` = g.`firstPiece` " +
                "GROUP BY g.`parentDocumentId`"
        )
        // A picture's SOURCE_TYPE metadata is its type again, in capitals: keep the two the same.
        db.execSQL(
            "UPDATE `vault_metadata` SET `value` = UPPER((SELECT `itemType` FROM `vault_items` WHERE `id` = `vault_metadata`.`vaultItemId`)) " +
                "WHERE `type` = 'SOURCE_TYPE' AND EXISTS (SELECT 1 FROM `vault_items` WHERE `id` = `vault_metadata`.`vaultItemId`)"
        )

        val documents = db.query("SELECT COUNT(*) FROM `documents`").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        onDone(report.copy(documents = documents))
    }

    private class Row(
        val rowId: Long, val text: String, val tags: String, val type: String,
        val mimeType: String?, val uri: String, val sourceFile: String,
    )

    /**
     * Splits each row's text and renames its type. Rows are read a few hundred at a time, by
     * rowid, and written only after the read is closed; a row that needs no change is left
     * untouched.
     */
    private fun rewriteItems(db: SupportSQLiteDatabase): MigrationReport {
        var rows = 0; var tagLines = 0; var qrItems = 0; var bracketEndings = 0
        val renamed = sortedMapOf<String, Int>()
        var after = Long.MIN_VALUE
        while (true) {
            val batch = ArrayList<Row>(BATCH)
            db.query(
                "SELECT `rowid`, `ocrText`, `tags`, `itemType`, `mimeType`, `uri`, `sourceFile` FROM `vault_items` " +
                    "WHERE `rowid` > ? ORDER BY `rowid` LIMIT $BATCH",
                arrayOf<Any>(after),
            ).use { c ->
                while (c.moveToNext()) {
                    batch += Row(
                        rowId = c.getLong(0), text = c.getString(1), tags = c.getString(2), type = c.getString(3),
                        mimeType = if (c.isNull(4)) null else c.getString(4), uri = c.getString(5), sourceFile = c.getString(6),
                    )
                }
            }
            if (batch.isEmpty()) break
            for (row in batch) {
                rows++
                val parts = FormerStoredText.split(row.text)
                val type = ItemType.ofStored(row.type)
                    ?: ItemType.fromFormer(row.type, row.mimeType, row.uri, row.sourceFile)
                val tags = listOf(row.tags.trim(), parts.tags).filter { it.isNotEmpty() }.joinToString(" ")
                val qrPayload = QrPayloads.join(parts.qrPayloads)

                if (parts.page.length != row.text.length) tagLines++
                else if (row.text.endsWith("]") && row.text.contains("\n[")) bracketEndings++
                if (qrPayload != null) qrItems++
                if (type.stored != row.type) renamed.merge("${row.type} → ${type.stored}", 1, Int::plus)

                if (parts.page.length == row.text.length && type.stored == row.type) continue
                db.execSQL(
                    "UPDATE `vault_items` SET `ocrText` = ?, `tags` = ?, `qrPayload` = ?, `itemType` = ? WHERE `rowid` = ?",
                    arrayOf<Any?>(parts.page, tags, qrPayload, type.stored, row.rowId),
                )
            }
            after = batch.last().rowId
        }
        return MigrationReport(
            ranAt = System.currentTimeMillis(), rows = rows, tagLinesMoved = tagLines, itemsWithQrCodes = qrItems,
            bracketEndingsKept = bracketEndings, typesRenamed = renamed, documents = 0,
        )
    }

    private companion object {
        const val BATCH = 200
    }
}

/**
 * What the version-14 migration did to this device's vault. It runs once, on a database nobody
 * else can look at, so it leaves its counts where the developer screen can show them.
 */
data class MigrationReport(
    val ranAt: Long,
    /** Rows of `vault_items` it looked at. */
    val rows: Int,
    /** Rows whose last line was a line of tags, now in `tags`. */
    val tagLinesMoved: Int,
    /** Pictures whose QR contents moved to `qrPayload`. */
    val itemsWithQrCodes: Int,
    /**
     * Rows that end with a bracketed line which is not a line of tags, and so was left in the
     * text. Expected to be 0 or a handful (a page that really ends that way); a large number
     * would mean tags written by a version of the app this one does not know.
     */
    val bracketEndingsKept: Int,
    /** "former name → name" and how many rows. */
    val typesRenamed: Map<String, Int>,
    /** Rows written to `documents`. */
    val documents: Int,
) {
    fun summary(): String = buildString {
        append("$rows items checked; $tagLinesMoved tag lines moved out of the text; ")
        append("$itemsWithQrCodes with QR contents; $documents documents recorded")
        if (bracketEndingsKept > 0) append("; $bracketEndingsKept bracketed endings left as text")
        if (typesRenamed.isNotEmpty()) {
            append(". Types renamed: ")
            append(typesRenamed.entries.joinToString(", ") { "${it.key} (${it.value})" })
        }
    }

    companion object {
        private const val PREFS = "vault_migrations"
        private const val KEY = "to_14"

        fun save(context: Context, report: MigrationReport) {
            VaultLog.i("VaultMigration", "13 → 14: ${report.summary()}")
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, "${report.ranAt}|${report.summary()}").apply()
        }

        /** When it ran and what it did, or null on a vault that was created at version 14. */
        fun load(context: Context): Pair<Long, String>? {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
            val at = saved.substringBefore('|').toLongOrNull() ?: return null
            return at to saved.substringAfter('|')
        }
    }
}
