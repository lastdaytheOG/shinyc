package com.amar.vault

import com.amar.vault.retrieval.KeywordWords

/**
 * One native keyword engine (`SearchEngine.cpp`), owned by this object: made with it, and
 * freed by [close]. The app has one for as long as it runs
 * ([com.amar.vault.retrieval.NativeBm25Index]); a test can make one of its own.
 *
 * Text and queries are passed as they are. The native side cuts them into words, by the table
 * [KeywordWords] gives it when the library is loaded — so what a word is, is decided in
 * Kotlin, and the cutting is done where it is fast.
 */
class NativeSearchEngine : AutoCloseable {
    companion object {
        init {
            System.loadLibrary("amar_search_engine")
            nativeInstallWords(KeywordWords.table)
        }

        /** The most ids one search returns (`SearchEngine::MAX_RESULTS`). */
        const val MAX_RESULTS = 300

        /** The words of [text] as the engine is given them, a space between them: for tests. */
        fun wordsOf(text: String): String = nativeWords(text)

        @JvmStatic private external fun nativeInstallWords(table: CharArray)
        @JvmStatic private external fun nativeWords(text: String): String
    }

    /** What the engine holds: for Developer Tools and tests. */
    data class Stats(val items: Long, val words: Long, val entries: Long, val removedKept: Long)

    private var handle: Long = nativeCreate()

    /** Adds an item, or replaces it when [docId] is already there: the words of [text] and of [more]. */
    @Synchronized
    fun add(docId: String, text: String, more: String = "") = nativeAdd(open(), docId, text, more)

    /** False when there was no such item. */
    @Synchronized
    fun remove(docId: String): Boolean = nativeRemove(open(), docId)

    /** Item ids, best first: at most [limit], and never more than [MAX_RESULTS]. */
    fun search(query: String, limit: Int = MAX_RESULTS): Array<String> = nativeSearch(open(), query, limit)

    @Synchronized
    fun clear() = nativeClear(open())

    fun stats(): Stats = nativeStats(open()).let { Stats(it[0], it[1], it[2], it[3]) }

    /** Frees the engine. Nothing may be using it; it cannot be used afterwards. */
    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    private fun open(): Long = handle.also { check(it != 0L) { "the keyword engine was closed" } }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeAdd(handle: Long, docId: String, text: String, more: String)
    private external fun nativeRemove(handle: Long, docId: String): Boolean
    private external fun nativeSearch(handle: Long, query: String, limit: Int): Array<String>
    private external fun nativeClear(handle: Long)
    private external fun nativeStats(handle: Long): LongArray
}
