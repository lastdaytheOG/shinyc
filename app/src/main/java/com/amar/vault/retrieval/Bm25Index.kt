package com.amar.vault.retrieval

import com.amar.vault.NativeSearchEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Boundary over the native keyword (BM25) engine.
 *
 * Justified as an interface by raw-JNI/FFI isolation (per the Phase 2A rule): JVM code
 * — including the write path (DocumentIndexer, hydration, share import) and the Phase 0B
 * JVM test tier — must not transitively link `System.loadLibrary`. It also gives the engine
 * a single, unambiguous owner (see [NativeBm25Index]).
 *
 * Callers pass text and queries as they are; what a word in them is, is decided in one place
 * ([KeywordWords]).
 */
interface Bm25Index {
    /**
     * Adds an item, or replaces it when [docId] is already there. It is indexed by the words
     * of [text] and of [more]: the same as passing the two with a space between them.
     */
    fun addDocument(docId: String, text: String, more: String = "")

    /** Takes an item out, so that it is no longer found. False when it was not there. */
    fun removeDocument(docId: String): Boolean

    fun removeDocuments(docIds: Collection<String>) = docIds.forEach { removeDocument(it) }

    /** Ids in rank order, best first: at most [limit], and never more than the engine's cap. */
    fun search(query: String, limit: Int = Int.MAX_VALUE): List<String>

    /** Drop all indexed documents (engine stays alive). */
    fun clear()

    /** How many items it holds and how many different words; null when it cannot say. */
    fun size(): Size? = null

    data class Size(val items: Long, val words: Long)
}

/**
 * The single owner of the native engine instance.
 *
 * Ownership responsibilities:
 *  - initialization: makes exactly one [NativeSearchEngine];
 *  - shutdown: [shutdown] (process-singleton ⇒ normally never invoked);
 *  - persistence: NONE by design — the engine is a rebuildable cache; the durable source of
 *    truth is Room, and it is filled from there when the app starts ([KeywordIndexFill],
 *    which has the measurements the decision was made on).
 *
 * The engine serializes its own writes and lets searches run alongside each other.
 */
class NativeBm25Index : Bm25Index {

    // Lazy so merely constructing/injecting this boundary does NOT link the native library
    // (System.loadLibrary runs in NativeSearchEngine's companion init). This upholds the
    // interface's stated rule — the write path and the JVM test tier must not transitively
    // load the .so — so a Robolectric/JVM test can build the object graph without an
    // UnsatisfiedLinkError; native loads only when an operation is actually invoked.
    private val engine by lazy { NativeSearchEngine() }

    override fun addDocument(docId: String, text: String, more: String) = engine.add(docId, text, more)

    override fun removeDocument(docId: String): Boolean = engine.remove(docId)

    override fun search(query: String, limit: Int): List<String> =
        if (limit <= 0) emptyList()
        else engine.search(query, minOf(limit, NativeSearchEngine.MAX_RESULTS)).asList()

    override fun clear() = engine.clear()

    override fun size(): Bm25Index.Size = engine.stats().let { Bm25Index.Size(it.items, it.words) }

    /** Assigned teardown (unused in normal process-singleton life). */
    fun shutdown() = engine.close()
}

/**
 * Bridge for the two components that are not (yet) Hilt-injected — the share-import worker
 * and any pre-injection Application access. Injected consumers depend on [Bm25Index]
 * directly; this exists only so those sites reach the same singleton instance.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface Bm25IndexEntryPoint {
    fun bm25Index(): Bm25Index
}
