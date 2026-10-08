package com.amar.vault.indexing

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * One file the vault was asked to read: where it is, how far the reading got, and how it
 * ended. A row is written before any reading starts and outlives the process, so a reading
 * that the system cuts short — a scan of several hundred pages takes longer than the ten
 * minutes background work is given — is carried on from the page it reached, and a file that
 * could not be read still says why the next time the app is opened.
 *
 * [id] is the id the document is stored under ([com.amar.vault.VaultDocument.id]); for a file
 * that was shared in it is the saved item's id.
 */
@Entity(tableName = "document_imports", indices = [Index("state")])
data class DocumentImport(
    @PrimaryKey val id: String,
    /** Where the file is read from. */
    val uri: String,
    /** What the file is called. */
    val name: String,
    /** The type its provider gave; may be blank or wrong, the name then says what it is. */
    val mimeType: String,
    val origin: ImportOrigin,
    val state: ImportState,
    /**
     * The hash of the file's bytes when its reading began. A reading is only carried on while
     * the file still has it; another file under the same address is read from its first page.
     */
    val fingerprint: String? = null,
    /** The last page whose text is stored, with every page before it. 0 before the first. */
    val pagesDone: Int = 0,
    /** How many pages the file has; null until it is opened, and for a file without pages. */
    val pageCount: Int? = null,
    /** How many pieces of its text are stored. */
    val pieces: Int = 0,
    /**
     * How many times in a row reading began and was stopped before it finished one more page.
     * A page that stops the app every time would otherwise be begun for ever.
     */
    val attempts: Int = 0,
    /** Pages given up on after [attempts] ran out; the rest of the file is read. */
    val pagesSkipped: Int = 0,
    /** Why it ended without its text, when [state] is [ImportState.FAILED]. */
    val failure: ImportFailure? = null,
    /** What the words about [failure] need to be exact: the file's extension, the error's own text. */
    val failureDetail: String? = null,
    val addedAt: Long,
    val updatedAt: Long = addedAt,
) {
    val isFinished: Boolean get() = state == ImportState.DONE || state == ImportState.FAILED || state == ImportState.ALREADY_THERE
}

enum class ImportState {
    /** Not started. */
    WAITING,
    /** Started; [DocumentImport.pagesDone] says how far it is. Also the state of one that was cut short. */
    READING,
    /** Its text is stored. */
    DONE,
    /** The same file was in the vault already; nothing was read. */
    ALREADY_THERE,
    /** Ended without its text; [DocumentImport.failure] says why. */
    FAILED,
}

enum class ImportOrigin {
    /** Chosen in the app with the file picker; read from where it sits. */
    PICKED,
    /** Shared into the app; read from the app's own copy. */
    SHARED,
}

@Dao
interface DocumentImportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(import: DocumentImport)

    @Query("SELECT * FROM document_imports WHERE id = :id")
    suspend fun getById(id: String): DocumentImport?

    /** Everything asked for, newest first: what the import screen lists. */
    @Query("SELECT * FROM document_imports ORDER BY addedAt DESC, rowid DESC")
    fun observeAll(): Flow<List<DocumentImport>>

    @Query("SELECT * FROM document_imports ORDER BY addedAt DESC, rowid DESC")
    suspend fun getAll(): List<DocumentImport>

    /** What is left to read, oldest first; one that was cut short comes before one not begun. */
    @Query("""
        SELECT * FROM document_imports WHERE state IN ('WAITING', 'READING')
        ORDER BY CASE state WHEN 'READING' THEN 0 ELSE 1 END, addedAt, rowid
    """)
    suspend fun unfinished(): List<DocumentImport>

    @Query("SELECT COUNT(*) FROM document_imports WHERE state IN ('WAITING', 'READING')")
    suspend fun countUnfinished(): Int

    /** A file at this address that is waiting or being read, if there is one. */
    @Query("SELECT * FROM document_imports WHERE uri = :uri AND state IN ('WAITING', 'READING') LIMIT 1")
    suspend fun unfinishedAt(uri: String): DocumentImport?

    @Query("""
        UPDATE document_imports SET state = 'READING', fingerprint = :fingerprint, pagesDone = :pagesDone,
            pieces = :pieces, attempts = attempts + 1, failure = NULL, failureDetail = NULL, updatedAt = :now
        WHERE id = :id
    """)
    suspend fun markReading(id: String, fingerprint: String?, pagesDone: Int, pieces: Int, now: Long)

    @Query("UPDATE document_imports SET pageCount = :pageCount, updatedAt = :now WHERE id = :id")
    suspend fun setPageCount(id: String, pageCount: Int, now: Long)

    @Query("UPDATE document_imports SET pagesDone = :page, pieces = :pieces, attempts = 0, updatedAt = :now WHERE id = :id")
    suspend fun markPageDone(id: String, page: Int, pieces: Int, now: Long)

    /** Gives up on the page after the last one done, so that reading starts at the one after it. */
    @Query("""
        UPDATE document_imports SET pagesDone = pagesDone + 1, pagesSkipped = pagesSkipped + 1,
            attempts = 0, updatedAt = :now
        WHERE id = :id
    """)
    suspend fun skipPage(id: String, now: Long)

    @Query("""
        UPDATE document_imports SET state = :state, pieces = :pieces, failure = :failure,
            failureDetail = :detail, updatedAt = :now
        WHERE id = :id
    """)
    suspend fun finish(id: String, state: ImportState, pieces: Int, failure: ImportFailure?, detail: String?, now: Long)

    /** Puts one that failed back in line; what was read of it is kept and carried on from. */
    @Query("""
        UPDATE document_imports SET state = 'WAITING', failure = NULL, failureDetail = NULL,
            attempts = 0, updatedAt = :now
        WHERE id = :id AND state = 'FAILED'
    """)
    suspend fun retry(id: String, now: Long): Int

    @Query("UPDATE document_imports SET uri = :uri, updatedAt = :now WHERE id = :id")
    suspend fun setUri(id: String, uri: String, now: Long)

    /** Takes finished rows off the list; what is waiting or being read stays. */
    @Query("DELETE FROM document_imports WHERE state IN ('DONE', 'ALREADY_THERE', 'FAILED')")
    suspend fun clearFinished()

    @Query("DELETE FROM document_imports WHERE id = :id")
    suspend fun delete(id: String)
}
