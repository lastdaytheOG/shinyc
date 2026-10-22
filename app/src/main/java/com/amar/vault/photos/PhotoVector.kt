package com.amar.vault.photos

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * What a picture looks like, as a vector a typed query can be compared with: one row per picture
 * and photo model ([PhotoModel]).
 *
 * The vector is kept as signed bytes with one scale ([PhotoVectors.quantize]): 768 bytes a
 * picture, 7.7 MB for 10,000 pictures.
 *
 * There is no foreign key to `vault_items`. Rows there are written with `INSERT OR REPLACE`, which
 * deletes the old row first, so a cascade would delete a picture's vector every time its row is
 * written again (as it is when its text has been read). A vector whose picture is gone is removed
 * by [PhotoVectorDao.deleteOrphans] and by [com.amar.vault.indexing.VaultRemoval].
 *
 * The key is the picture and the model together. A newer model's vectors are made beside the old
 * ones, and the old ones go only once every picture has one from the newer model
 * ([PhotoBackfillWorker]): until then, going back is switching the model back.
 */
@Entity(tableName = "photo_vectors", primaryKeys = ["itemId", "model"], indices = [Index("model")])
data class PhotoVector(
    /** The picture's row in `vault_items`. */
    val itemId: String,
    /** The [PhotoModel.id] that made the vector; a query is only compared with its own model's. */
    val model: String,
    /** The picture's perceptual hash when it was embedded: a different picture under the same id is embedded again. */
    val pHash: Long,
    /** Dimension i of the vector is `vector[i] * scale`. */
    val vector: ByteArray,
    val scale: Float,
    val madeAt: Long,
) {
    override fun equals(other: Any?): Boolean = other is PhotoVector && itemId == other.itemId &&
        model == other.model && pHash == other.pHash && vector.contentEquals(other.vector) &&
        scale == other.scale && madeAt == other.madeAt

    override fun hashCode(): Int = (itemId.hashCode() * 31 + model.hashCode()) * 31 + vector.contentHashCode()
}

/** A picture that still needs a vector: what embedding it reads. */
data class PictureToEmbed(val id: String, val uri: String, val pHash: Long)

@Dao
interface PhotoVectorDao {

    /** Nothing refers to a row here, so replacing one deletes nothing else. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vector: PhotoVector)

    @Query("SELECT * FROM photo_vectors WHERE model = :model")
    suspend fun allOf(model: String): List<PhotoVector>

    @Query("SELECT * FROM photo_vectors WHERE model = :model AND itemId = :itemId")
    suspend fun get(model: String, itemId: String): PhotoVector?

    @Query("SELECT COUNT(*) FROM photo_vectors WHERE model = :model")
    suspend fun countOf(model: String): Int

    /**
     * Pictures with no vector from [model] yet, or whose picture changed since (another
     * perceptual hash), newest first.
     */
    @Query(
        """
        SELECT v.id AS id, v.uri AS uri, v.pHash AS pHash FROM vault_items v
        WHERE v.parentDocumentId IS NULL AND v.itemType IN ('photo', 'screenshot')
          AND NOT EXISTS (SELECT 1 FROM photo_vectors p WHERE p.itemId = v.id AND p.model = :model AND p.pHash = v.pHash)
        ORDER BY v.timestamp DESC LIMIT :limit
        """
    )
    suspend fun picturesWithout(model: String, limit: Int): List<PictureToEmbed>

    /** How many pictures still need a vector from [model]. */
    @Query(
        """
        SELECT COUNT(*) FROM vault_items v
        WHERE v.parentDocumentId IS NULL AND v.itemType IN ('photo', 'screenshot')
          AND NOT EXISTS (SELECT 1 FROM photo_vectors p WHERE p.itemId = v.id AND p.model = :model AND p.pHash = v.pHash)
        """
    )
    suspend fun countWithout(model: String): Int

    /** Vectors of pictures that are no longer in the vault. Returns how many went. */
    @Query("DELETE FROM photo_vectors WHERE itemId NOT IN (SELECT id FROM vault_items)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM photo_vectors WHERE itemId IN (:itemIds)")
    suspend fun deleteItems(itemIds: List<String>): Int

    /** Every vector not made by [model]: once a newer model has replaced them all. */
    @Query("DELETE FROM photo_vectors WHERE model != :model")
    suspend fun deleteAllBut(model: String): Int
}
