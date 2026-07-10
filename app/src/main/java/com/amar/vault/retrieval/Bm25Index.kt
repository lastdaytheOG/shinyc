package com.amar.vault.retrieval

import com.amar.vault.NativeSearchEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Boundary over the native BM25 engine.
 *
 * Justified as an interface by raw-JNI/FFI isolation (per the Phase 2A rule): JVM code
 * — including the write path (DocumentIndexer, hydration, share import) and the Phase 0B
 * JVM test tier — must not transitively link `System.loadLibrary`. It also gives the BM25
 * engine a single, unambiguous owner (see [NativeBm25Index]), retiring the ownerless
 * `SearchEngineHolder` global whose "MANDATORY" `destroyEngine()` was never called.
 */
interface Bm25Index {
    /** Index/re-index a document. Serialized against other writes. */
    fun addDocument(docId: String, text: String)
    /** BM25 candidate ids in rank order (engine caps the result set). */
    fun search(query: String): List<String>
    /** Drop all indexed documents (engine stays alive). */
    fun clear()
}

/**
 * The single owner of the native BM25 engine instance.
 *
 * Ownership responsibilities:
 *  - initialization: constructs + `initEngine()`s exactly one [NativeSearchEngine];
 *  - shutdown: `destroyEngine()` via [shutdown] (process-singleton ⇒ normally never
 *    invoked, but ownership is now assigned to one place);
 *  - persistence: NONE by design — BM25 is a rebuildable cache; the durable source of
 *    truth is Room, and rebuild-on-launch is orchestrated by the app startup;
 *  - recovery: rebuild-from-Room (same rebuild path).
 *
 * Writes are serialized here (the mutex previously scattered in DocumentIndexer); reads
 * are lock-free, matching prior behaviour.
 */
class NativeBm25Index : Bm25Index {

    private val engine = NativeSearchEngine().also { it.initEngine() }

    @Synchronized
    override fun addDocument(docId: String, text: String) = engine.addDocument(docId, text)

    override fun search(query: String): List<String> = engine.search(query).toList()

    @Synchronized
    override fun clear() = engine.clear()

    /** Assigned teardown (unused in normal process-singleton life). */
    fun shutdown() = engine.destroyEngine()
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
