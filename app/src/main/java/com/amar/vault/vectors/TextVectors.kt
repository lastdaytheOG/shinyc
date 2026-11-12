package com.amar.vault.vectors

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "text_vectors", primaryKeys = ["rowId", "model", "part"], indices = [Index("model")])
data class TextVector(
    val rowId: String,
    val model: String,
    val part: Int = 0,
    val textHash: String,
    val vector: ByteArray,
    val scale: Float,
    val madeAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is TextVector && rowId == other.rowId && model == other.model &&
        part == other.part && textHash == other.textHash && vector.contentEquals(other.vector) && scale == other.scale &&
        madeAt == other.madeAt

    override fun hashCode(): Int = ((rowId.hashCode() * 31 + model.hashCode()) * 31 + part) * 31 + vector.contentHashCode()
}

data class StoredHash(val rowId: String, val part: Int, val textHash: String)

data class RowToEmbed(
    val id: String,
    val ocrText: String,
    val sourceFile: String,
    val title: String?,
    val parentDocumentId: String?,
    val pageNum: Int,
    val chunkIndex: Int,
    val itemType: String,
    val timestamp: Long,
)

@Dao
interface TextVectorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(vectors: List<TextVector>)

    @Query("SELECT * FROM text_vectors WHERE model = :model")
    suspend fun allOf(model: String): List<TextVector>

    @Query("SELECT * FROM text_vectors WHERE model = :model AND rowId IN (:rowIds)")
    suspend fun of(model: String, rowIds: List<String>): List<TextVector>

    @Query("SELECT rowId, part, textHash FROM text_vectors WHERE model = :model")
    suspend fun hashesOf(model: String): List<StoredHash>

    @Query("SELECT COUNT(DISTINCT rowId) FROM text_vectors WHERE model = :model")
    suspend fun rowsWithVector(model: String): Int

    @Query("DELETE FROM text_vectors WHERE rowId IN (:rowIds)")
    suspend fun deleteRows(rowIds: List<String>): Int

    @Query("DELETE FROM text_vectors WHERE model = :model AND rowId = :rowId AND part = :part")
    suspend fun delete(model: String, rowId: String, part: Int): Int

    @Query("DELETE FROM text_vectors WHERE rowId NOT IN (SELECT id FROM vault_items)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM text_vectors WHERE model != :model")
    suspend fun deleteAllBut(model: String): Int

    @Query(
        """
        SELECT v.id AS id, v.ocrText AS ocrText, v.sourceFile AS sourceFile, v.title AS title,
               v.parentDocumentId AS parentDocumentId, v.pageNum AS pageNum, v.chunkIndex AS chunkIndex,
               v.itemType AS itemType, v.timestamp AS timestamp
        FROM vault_items v
        LEFT JOIN coverage c ON c.itemId = COALESCE(v.parentDocumentId, v.id)
        WHERE length(trim(v.ocrText)) >= :minChars
          AND NOT EXISTS (SELECT 1 FROM text_vectors t WHERE t.rowId = v.id AND t.model = :model)
          AND (:tier >= 2 OR v.parentDocumentId IS NULL OR NOT EXISTS (
                SELECT 1 FROM vault_items p WHERE p.parentDocumentId = v.parentDocumentId
                  AND p.pageNum = v.pageNum AND p.chunkIndex < v.chunkIndex))
        ORDER BY COALESCE(c.priority, 0) DESC, v.timestamp DESC, v.id
        LIMIT :limit
        """
    )
    suspend fun rowsWithout(model: String, tier: Int, minChars: Int, limit: Int): List<RowToEmbed>

    @Query(
        """
        SELECT COUNT(*) FROM vault_items v
        WHERE length(trim(v.ocrText)) >= :minChars
          AND NOT EXISTS (SELECT 1 FROM text_vectors t WHERE t.rowId = v.id AND t.model = :model)
          AND (:tier >= 2 OR v.parentDocumentId IS NULL OR NOT EXISTS (
                SELECT 1 FROM vault_items p WHERE p.parentDocumentId = v.parentDocumentId
                  AND p.pageNum = v.pageNum AND p.chunkIndex < v.chunkIndex))
        """
    )
    suspend fun countWithout(model: String, tier: Int, minChars: Int): Int

    @Query(
        """
        SELECT v.id AS id, v.ocrText AS ocrText, v.sourceFile AS sourceFile, v.title AS title,
               v.parentDocumentId AS parentDocumentId, v.pageNum AS pageNum, v.chunkIndex AS chunkIndex,
               v.itemType AS itemType, v.timestamp AS timestamp
        FROM vault_items v WHERE v.id IN (:ids)
        """
    )
    suspend fun rows(ids: List<String>): List<RowToEmbed>
}

@Entity(tableName = "coverage")
data class Coverage(
    @PrimaryKey val itemId: String,
    val tier: Int,
    val rowsTotal: Int,
    val rowsWithVector: Int,
    val model: String?,
    val priority: Int = 0,
    val updatedAt: Long,
)

data class CoverageTotals(val items: Int, val rowsTotal: Int, val rowsWithVector: Int)

@Dao
interface CoverageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: Coverage)

    @Query("SELECT * FROM coverage WHERE itemId = :itemId")
    suspend fun get(itemId: String): Coverage?

    @Query("UPDATE coverage SET priority = MAX(priority, :priority), updatedAt = :at WHERE itemId IN (:itemIds)")
    suspend fun raise(itemIds: List<String>, priority: Int, at: Long): Int

    @Query("INSERT OR IGNORE INTO coverage (itemId, tier, rowsTotal, rowsWithVector, model, priority, updatedAt) VALUES (:itemId, 0, 0, 0, NULL, :priority, :at)")
    suspend fun ensure(itemId: String, priority: Int, at: Long)

    @Query(
        """
        INSERT OR REPLACE INTO coverage (itemId, tier, rowsTotal, rowsWithVector, model, priority, updatedAt)
        SELECT d.item,
               CASE WHEN d.total > 0 AND d.withVector >= d.total THEN 2 WHEN d.withVector > 0 THEN 1 ELSE 0 END,
               d.total, d.withVector, :model, COALESCE((SELECT priority FROM coverage c WHERE c.itemId = d.item), 0), :at
        FROM (
            SELECT COALESCE(v.parentDocumentId, v.id) AS item,
                   SUM(CASE WHEN length(trim(v.ocrText)) >= :minChars THEN 1 ELSE 0 END) AS total,
                   SUM(CASE WHEN length(trim(v.ocrText)) >= :minChars AND EXISTS (SELECT 1 FROM text_vectors t WHERE t.rowId = v.id AND t.model = :model) THEN 1 ELSE 0 END) AS withVector
            FROM vault_items v WHERE COALESCE(v.parentDocumentId, v.id) IN (:items)
            GROUP BY COALESCE(v.parentDocumentId, v.id)
        ) d
        """
    )
    suspend fun refresh(items: List<String>, model: String, minChars: Int, at: Long)

    @Query(
        """
        INSERT OR REPLACE INTO coverage (itemId, tier, rowsTotal, rowsWithVector, model, priority, updatedAt)
        SELECT d.item,
               CASE WHEN d.total > 0 AND d.withVector >= d.total THEN 2 WHEN d.withVector > 0 THEN 1 ELSE 0 END,
               d.total, d.withVector, :model, COALESCE((SELECT priority FROM coverage c WHERE c.itemId = d.item), 0), :at
        FROM (
            SELECT COALESCE(v.parentDocumentId, v.id) AS item,
                   SUM(CASE WHEN length(trim(v.ocrText)) >= :minChars THEN 1 ELSE 0 END) AS total,
                   SUM(CASE WHEN length(trim(v.ocrText)) >= :minChars AND EXISTS (SELECT 1 FROM text_vectors t WHERE t.rowId = v.id AND t.model = :model) THEN 1 ELSE 0 END) AS withVector
            FROM vault_items v GROUP BY COALESCE(v.parentDocumentId, v.id)
        ) d
        """
    )
    suspend fun refreshAll(model: String, minChars: Int, at: Long)

    @Query("SELECT COUNT(*) AS items, COALESCE(SUM(rowsTotal), 0) AS rowsTotal, COALESCE(SUM(rowsWithVector), 0) AS rowsWithVector FROM coverage WHERE rowsTotal > 0")
    suspend fun totals(): CoverageTotals

    @Query("DELETE FROM coverage WHERE itemId NOT IN (SELECT COALESCE(parentDocumentId, id) FROM vault_items)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM coverage WHERE itemId IN (:itemIds)")
    suspend fun deleteItems(itemIds: List<String>): Int
}
