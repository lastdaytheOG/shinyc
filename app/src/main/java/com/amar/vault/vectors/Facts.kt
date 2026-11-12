package com.amar.vault.vectors

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(
    tableName = "facts",
    indices = [
        Index(value = ["kind", "valueNum"], name = "index_facts_kind_num"),
        Index(value = ["kind", "valueDate"], name = "index_facts_kind_date"),
        Index(value = ["kind", "valueText"], name = "index_facts_kind_text"),
        Index(value = ["itemId"], name = "index_facts_item"),
    ],
)
data class Fact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: String,
    val rowId: String,
    val kind: String,
    val valueText: String,
    val valueNum: Double? = null,
    val valueDate: Long? = null,
    val spanStart: Int,
    val spanEnd: Int,
    val trust: Float,
    val ruleVersion: Int,
)

object FactKind {
    const val AMOUNT = "AMOUNT"
    const val DATE = "DATE"
    const val ID_NUMBER = "ID_NUMBER"
    const val PARTY = "PARTY"
    const val DOC_KIND = "DOC_KIND"
    const val PHONE = "PHONE"
}

@Dao
interface FactDao {

    @Insert
    suspend fun insertAll(facts: List<Fact>)

    @Query("DELETE FROM facts WHERE rowId IN (:rowIds)")
    suspend fun deleteRows(rowIds: List<String>): Int

    @Query("DELETE FROM facts WHERE itemId IN (:itemIds)")
    suspend fun deleteItems(itemIds: List<String>): Int

    @Query("DELETE FROM facts WHERE rowId NOT IN (SELECT id FROM vault_items)")
    suspend fun deleteOrphans(): Int

    @Query("SELECT * FROM facts WHERE itemId = :itemId ORDER BY spanStart")
    suspend fun of(itemId: String): List<Fact>

    @Query("SELECT * FROM facts WHERE kind = :kind AND valueNum BETWEEN :lo AND :hi")
    suspend fun numbersBetween(kind: String, lo: Double, hi: Double): List<Fact>

    @Query("SELECT * FROM facts WHERE kind = :kind AND valueDate BETWEEN :lo AND :hi")
    suspend fun datesBetween(kind: String, lo: Long, hi: Long): List<Fact>

    @Query("SELECT * FROM facts WHERE kind = :kind AND valueText = :value")
    suspend fun textEquals(kind: String, value: String): List<Fact>

    @Query("SELECT COUNT(*) FROM facts")
    suspend fun count(): Int

    @Query("SELECT DISTINCT rowId FROM facts WHERE ruleVersion = :version")
    suspend fun rowsAtVersion(version: Int): List<String>
}

@Entity(tableName = "aliases", primaryKeys = ["term", "standsFor"])
data class Alias(
    val term: String,
    val standsFor: String,
    val weight: Float,
    val learnedAt: Long,
)

@Dao
interface AliasDao {

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(alias: Alias)

    @Query("SELECT * FROM aliases WHERE term IN (:terms)")
    suspend fun forTerms(terms: List<String>): List<Alias>

    @Query("SELECT * FROM aliases ORDER BY learnedAt DESC")
    suspend fun all(): List<Alias>

    @Query("DELETE FROM aliases WHERE term = :term AND standsFor = :standsFor")
    suspend fun delete(term: String, standsFor: String): Int

    @Query("DELETE FROM aliases")
    suspend fun clear(): Int
}

@Entity(tableName = "opens", indices = [Index("queryNorm"), Index("itemId")])
data class Open(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val queryNorm: String,
    val itemId: String,
    val verdict: String,
    val at: Long,
)

@Dao
interface OpenDao {

    @Insert
    suspend fun insert(open: Open)

    @Query("SELECT * FROM opens WHERE queryNorm = :queryNorm AND at >= :since")
    suspend fun forQuery(queryNorm: String, since: Long): List<Open>

    @Query("SELECT * FROM opens ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Open>

    @Query("SELECT COUNT(*) FROM opens")
    suspend fun count(): Int

    @Query("DELETE FROM opens WHERE at < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM opens")
    suspend fun clear(): Int
}
