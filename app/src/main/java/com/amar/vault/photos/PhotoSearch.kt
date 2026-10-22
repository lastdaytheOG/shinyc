package com.amar.vault.photos

import android.content.Context
import android.graphics.Bitmap
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Searching pictures by what is in them: the one owner of photo search's models, of the vectors
 * held in memory, and of turning a picture or a query into a vector.
 *
 * The models live in `files/photo-search/` ([PhotoModelFiles]). Without them nothing here does any
 * work: [isReady] is false, search lists no photos and indexing skips the vector, so a build without
 * the models behaves exactly as before. Pictures indexed meanwhile are embedded later by
 * [PhotoBackfillWorker].
 *
 * Every picture's vector is stored in Room ([PhotoVector]); this holds those of the model in use
 * in a [PhotoIndex], filled from Room on first use and kept up to date as pictures are embedded.
 */
class PhotoSearch(private val context: Context, private val db: VaultDatabase) {

    private val files = PhotoModelFiles(context)
    private val encodeLock = Mutex()

    /**
     * Where the models load: not in the caller's coroutine. Each keystroke cancels the search
     * before it, and a load cancelled with it used to count as a failed one, which turned photo
     * search off for the rest of the session ("Child of the scoped flow was cancelled").
     */
    private val loader = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var loading: Deferred<Boolean>? = null

    @Volatile private var query: QueryEncoder? = null
    @Volatile private var photo: PhotoEncoder? = null
    @Volatile private var index: PhotoIndex? = null
    @Volatile private var failed: String? = null

    /** The models are on the device and loaded, or can be. */
    val isReady: Boolean get() = failed == null && (index != null || files.present() || files.bundled())

    /** The [PhotoVector.model] id of the vectors in use; null without models. */
    val modelId: String? get() = files.manifest()?.id

    /** Why photo search is off, when it is: for Developer Tools. */
    val problem: String? get() = failed ?: if (!files.present() && !files.bundled()) "the photo search models are not on this device" else null

    /**
     * Loads the models and the stored vectors once; false when they are not here or did not load.
     * Every caller waits for the same load ([loader]); a caller that is cancelled stops waiting,
     * and the load goes on for the next one.
     */
    private suspend fun ensureLoaded(): Boolean {
        if (index != null) return true
        if (failed != null) return false
        val load = synchronized(this) { loading ?: loader.async { load() }.also { loading = it } }
        val loaded = load.await()
        // Models not on the device yet are not a failure: let a later call look again.
        if (!loaded && failed == null) synchronized(this) { if (loading === load) loading = null }
        return loaded
    }

    private suspend fun load(): Boolean {
        if (!files.installFromApk()) return false
        return try {
            val manifest = files.manifest() ?: error("no manifest")
            val tokenizer = files.tokenizer().inputStream().use { GemmaTokenizer.read(it) }
            val q = QueryEncoder(files.queryModel().path, tokenizer)
            val p = PhotoEncoder(files.photoModel().path, manifest.inputSize)
            check(q.dim == manifest.dim && p.dim == manifest.dim) {
                "model dimensions differ: query ${q.dim}, photo ${p.dim}, manifest ${manifest.dim}"
            }
            val idx = PhotoIndex(manifest.dim)
            idx.load(db.photoVectorDao().allOf(manifest.id))
            query = q; photo = p; index = idx
            VaultLog.i(TAG, "photo search ready: ${manifest.id}, ${idx.size} pictures")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = "the photo search models could not be loaded: ${e.message}"
            VaultLog.e(TAG, failed!!)
            false
        }
    }

    /** Starts loading the models now, so the first search does not wait for them. */
    fun warmUp() {
        if (index == null && failed == null && (files.present() || files.bundled())) {
            loader.launch { ensureLoaded() }
        }
    }

    /** The [k] pictures that look most like [text], most alike first; empty without models. */
    suspend fun search(text: String, k: Int, only: ((String) -> Boolean)? = null): List<PhotoMatch> {
        if (text.isBlank() || !ensureLoaded()) return emptyList()
        val idx = index ?: return emptyList()
        if (idx.size == 0) return emptyList()
        return withContext(Dispatchers.Default) {
            val v = query!!.encode(text)
            idx.top(v, k, only)
        }
    }

    /** The side of the square the photo model reads; null without models. */
    val inputSize: Int? get() = files.manifest()?.inputSize

    /**
     * Embeds one picture and stores its vector. [bitmap] may be the picture at any size; it is
     * squashed to the model's square. Returns false when photo search has no models.
     */
    suspend fun embed(itemId: String, pHash: Long, bitmap: Bitmap): Boolean {
        val size = inputSize ?: return false
        return embedPixels(itemId, pHash, PhotoPixels.of(bitmap, size))
    }

    /**
     * The same from pixels already prepared for the model ([PhotoPixels.of] at [inputSize]): the
     * indexing pipeline prepares them while it still holds the decoded picture, and embeds later.
     */
    suspend fun embedPixels(itemId: String, pHash: Long, pixels: FloatArray): Boolean {
        if (!ensureLoaded()) return false
        val manifest = files.manifest() ?: return false
        // One picture at a time: the model already uses several cores, and a burst of new
        // pictures run side by side would only fight over them.
        val vector = encodeLock.withLock { withContext(Dispatchers.Default) { photo!!.encode(pixels) } }
        val (bytes, scale) = PhotoVectors.quantize(vector)
        db.photoVectorDao().upsert(PhotoVector(itemId, manifest.id, pHash, bytes, scale, System.currentTimeMillis()))
        index?.put(itemId, bytes, scale)
        return true
    }

    /** Forgets pictures that left the vault (their rows are gone). */
    suspend fun forget(itemIds: Collection<String>) {
        if (itemIds.isEmpty()) return
        db.photoVectorDao().deleteItems(itemIds.toList())
        index?.let { idx -> itemIds.forEach { idx.remove(it) } }
    }

    /** Pictures that still need a vector from the model in use. */
    suspend fun pending(): Int = modelId?.let { db.photoVectorDao().countWithout(it) } ?: 0

    /** Puts the models where they are loaded from (out of the APK, once); loads nothing. */
    suspend fun install(): Boolean = withContext(Dispatchers.IO) { files.installFromApk() }

    /** Pictures held in memory for search. */
    val indexed: Int get() = index?.size ?: 0

    /** Encoders for Developer Tools' timing; null without models. */
    suspend fun encodersForBenchmark(): Pair<QueryEncoder, PhotoEncoder>? =
        if (ensureLoaded()) query!! to photo!! else null

    companion object {
        private const val TAG = "PhotoSearch"
    }
}

/** What `files/photo-search/manifest.json` says about the models: see `tools/visual-search`. */
data class PhotoModelManifest(val id: String, val dim: Int, val inputSize: Int, val queryModel: String, val photoModel: String)

/**
 * Where photo search's files are: `files/photo-search/` holds `manifest.json`, the tokenizer table
 * (`gemma_bpe.bin`), the query model (EmbeddingGemma 2's text model, with its `_data` file beside
 * it) and the photo model. A build made with `-PphotoSearchModels` carries them in its assets and
 * they are copied here the first time; otherwise they are put here by Developer Tools or adb.
 */
class PhotoModelFiles(private val context: Context) {

    val dir: File get() = File(context.filesDir, DIR)

    @Volatile private var cached: PhotoModelManifest? = null

    /** The manifest of the models on the device; reads a small file, copies nothing. */
    fun manifest(): PhotoModelManifest? {
        cached?.let { return it }
        val f = File(dir, MANIFEST)
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            PhotoModelManifest(
                id = j.getString("id"), dim = j.getInt("dim"), inputSize = j.getInt("inputSize"),
                queryModel = j.getString("queryModel"), photoModel = j.getString("photoModel"),
            ).also { cached = it }
        } catch (e: Exception) {
            VaultLog.w("PhotoSearch", "unreadable photo search manifest: ${e.message}")
            null
        }
    }

    /** Every model file is on the device. */
    fun present(): Boolean = manifest()?.let(::complete) == true

    /** This APK carries the models (a build made with `-PphotoSearchModels`). */
    fun bundled(): Boolean = try {
        context.assets.list(DIR)?.contains(MANIFEST) == true
    } catch (e: Exception) {
        false
    }

    /** Copies the models out of the APK when they are not on the device yet. Slow: call off the main thread. */
    fun installFromApk(): Boolean = present() || (bundled() && copyFromAssets() && run { cached = null; present() })

    private fun complete(m: PhotoModelManifest): Boolean =
        tokenizer().exists() && File(dir, m.queryModel).exists() && File(dir, m.photoModel).exists()

    fun tokenizer(): File = File(dir, TOKENIZER)
    fun queryModel(): File = File(dir, manifest()!!.queryModel)
    fun photoModel(): File = File(dir, manifest()!!.photoModel)

    /**
     * Copies the models out of the APK, each file whole or not at all (written to `.part`, then
     * renamed), the manifest last: a copy cut short is finished by the next call, never mistaken
     * for a complete set.
     */
    private fun copyFromAssets(): Boolean = synchronized(this) {
        val names = try { context.assets.list(DIR)?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        if (MANIFEST !in names) return false
        dir.mkdirs()
        for (name in names.filter { it != MANIFEST } + MANIFEST) {
            val target = File(dir, name)
            if (target.exists()) continue
            val partial = File(dir, "$name.part")
            try {
                context.assets.open("$DIR/$name").use { input -> partial.outputStream().use { input.copyTo(it, 8 shl 20) } }
            } catch (e: Exception) {
                partial.delete()
                VaultLog.w("PhotoSearch", "copying $name out of the APK failed: ${e.message}")
                return false
            }
            if (!partial.renameTo(target)) { partial.delete(); return false }
        }
        true
    }

    companion object {
        const val DIR = "photo-search"
        const val MANIFEST = "manifest.json"
        const val TOKENIZER = "gemma_bpe.bin"
    }
}
