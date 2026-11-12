package com.amar.vault.retrieval

import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import kotlinx.coroutines.flow.first

/**
 * Lexical (keyword) recall capability. Wraps the native BM25 engine, and — for the
 * candidate-bounded path only — the already-existing FTS4 index. Returns document ids in
 * rank order, bounded by the caller's budget. No ranking policy lives here.
 */
interface LexicalRetriever {
    /** Native BM25 candidate ids (already capped by the engine); bounded to [limit]. */
    fun bm25(query: String, limit: Int): List<String>

    /**
     * Returns once the engine holds everything that was stored when the app started. Until
     * then [bm25] answers from part of the vault.
     */
    suspend fun awaitReady() {}

    /** FTS4 token-match candidate ids. Only used by the candidate-bounded path. */
    suspend fun fts(query: String, limit: Int): List<String>
}

class DefaultLexicalRetriever(
    private val db: VaultDatabase,
    private val bm25Index: Bm25Index,
    private val fill: KeywordIndexFill? = null,
) : LexicalRetriever {

    override fun bm25(query: String, limit: Int): List<String> =
        bm25Index.search(query, limit)

    override suspend fun awaitReady() {
        fill?.awaitFilled()
    }

    override suspend fun fts(query: String, limit: Int): List<String> = try {
        // FTS4 MATCH; guarded because raw queries can contain characters FTS rejects.
        db.vaultDao().searchFts(query).first().map { it.id }.take(limit)
    } catch (e: Exception) {
        VaultLog.d("LexicalRetriever", "FTS skipped: ${e.message}")
        emptyList()
    }
}
