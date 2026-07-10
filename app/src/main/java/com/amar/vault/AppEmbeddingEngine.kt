package com.amar.vault

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton wrapper around [EmbeddingEngine] (ONNX BGE-M3).
 *
 * BGE-M3 produces better embeddings when the input is prefixed:
 * - Indexing (passages): no prefix needed
 * - Searching (queries): prefix with "query: "
 *
 * Use [embedPassage] during indexing and [embedQuery] during search.
 * The generic [embed] is kept for backward compatibility.
 */
object AppEmbeddingEngine {

    @Volatile
    private var instance: EmbeddingEngine? = null

    val readyState = MutableStateFlow(false)

    private const val QUERY_PREFIX   = "query: "
    private const val PASSAGE_PREFIX = ""

    fun initialize(context: Context) {
        if (instance != null) return
        synchronized(this) {
            if (instance == null) {
                instance = EmbeddingEngine(context.applicationContext)
                readyState.value = true
            }
        }
    }

    fun get(context: Context): EmbeddingEngine {
        return instance ?: synchronized(this) {
            instance ?: EmbeddingEngine(context.applicationContext).also {
                instance = it
                readyState.value = true
            }
        }
    }

    // ── Prefixed embedding methods (suspend — must call from coroutine) ──

    /**
     * Embed a search query. Prepends "query: " prefix for BGE-M3.
     * Call from a coroutine (IO dispatcher).
     */
    suspend fun embedQuery(context: Context, text: String): FloatArray {
        return get(context).embed(QUERY_PREFIX + text)
    }

    /**
     * Embed a document passage during indexing. No prefix for BGE-M3.
     * Call from a coroutine (IO dispatcher).
     */
    suspend fun embedPassage(context: Context, text: String): FloatArray {
        return get(context).embed(PASSAGE_PREFIX + text)
    }

    /**
     * Generic embed — prefer [embedQuery] or [embedPassage].
     * Kept for backward compatibility.
     */
    suspend fun embed(context: Context, text: String): FloatArray {
        return get(context).embed(text)
    }

    fun release() {
        synchronized(this) {
            instance?.close()
            instance = null
            readyState.value = false
        }
    }
}