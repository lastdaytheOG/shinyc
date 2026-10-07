package com.amar.vault.dev

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.amar.vault.DocumentIndexer
import com.amar.vault.IndexError
import com.amar.vault.IndexResult
import com.amar.vault.IndexingPipeline
import com.amar.vault.ScanPreferences
import com.amar.vault.VaultConfig
import com.amar.vault.indexing.DiscoveryEngine
import com.amar.vault.indexing.DiscoverySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Developer-only manual indexing trigger.
 *
 * This is NOT a new indexing implementation — it is a *trigger* in exactly the sense the
 * [DiscoveryEngine] doc calls out ("scheduling, lifecycle, trigger-specific behaviour … stay in
 * the triggers"). It discovers candidates via the shared [DiscoveryEngine] and hands each item to
 * the existing engines untouched: images to [IndexingPipeline.indexBitmap], documents to
 * [DocumentIndexer.indexDocument]. All dedup, OCR, embedding, BM25, and hashing decisions remain
 * owned by those engines. This class only adds pause / resume / cancel orchestration and a
 * progress [StateFlow] for the developer UI.
 *
 * Production indexing (BulkScanWorker / foreground / nightly) is entirely unaffected — this shares
 * their engines but runs on its own opt-in coroutine, reachable only in Developer Mode.
 */
object DeveloperIndexController {

    enum class Phase { IDLE, RUNNING, PAUSED, DONE, CANCELLED, ERROR }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val label: String = "",
        val total: Int = 0,
        val processed: Int = 0,
        val indexed: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val durationMs: Long = 0L,
        val note: String? = null,
    )

    /** Preset MediaStore image folders (RELATIVE_PATH substrings, matching production scan folders). */
    enum class ImagePreset(val label: String, val folders: List<String>) {
        DOWNLOADS("Downloads", listOf("Download", "Downloads")),
        DOCUMENTS("Documents", listOf("Documents", "Document")),
        PICTURES("Pictures", listOf("Pictures")),
        SCREENSHOTS("Screenshots", listOf("Screenshots", "DCIM/Screenshots")),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var paused = false
    @Volatile private var cancelled = false
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    // ── Controls ──────────────────────────────────────────────────────────────

    fun pause() {
        if (isBusy && !paused) {
            paused = true
            _status.value = _status.value.copy(phase = Phase.PAUSED)
        }
    }

    fun resume() {
        if (isBusy && paused) {
            paused = false
            _status.value = _status.value.copy(phase = Phase.RUNNING)
        }
    }

    fun cancel() {
        if (isBusy) {
            cancelled = true
            paused = false
        }
    }

    // ── Public run entry points ────────────────────────────────────────────────

    /** Discover images in [preset] via the shared DiscoveryEngine, then index. */
    fun indexImagePreset(context: Context, preset: ImagePreset, force: Boolean) {
        val app = context.applicationContext
        launch("Index images: ${preset.label}${if (force) " (re-index)" else ""}") {
            val engine = DiscoveryEngine(app)
            val spec = DiscoverySpec(
                mediaScope = DiscoverySpec.MediaScope.ALL_VOLUMES,
                folders = preset.folders,
                sinceEpochSeconds = null,
                // force ⇒ don't pre-filter already-indexed at discovery. (The pipeline still applies
                // its own pHash dedup per image — this is the existing, behaviour-preserving contract.)
                dedupAgainstIndexed = !force,
            )
            val uris = engine.discover(spec)
            processImages(app, uris)
        }
    }

    /** Full re-index across the user's configured scan folders (reuses production folder set). */
    fun fullReindex(context: Context) {
        val app = context.applicationContext
        launch("Full re-index") {
            val folders = ScanPreferences.prefsFlow(app).first().foldersToScan()
            val engine = DiscoveryEngine(app)
            val spec = DiscoverySpec(
                mediaScope = DiscoverySpec.MediaScope.ALL_VOLUMES,
                folders = folders,
                sinceEpochSeconds = null,
                dedupAgainstIndexed = false,
            )
            val uris = engine.discover(spec)
            processImages(app, uris)
        }
    }

    /** Index a hand-picked set of content URIs, classified by mime into image vs document. */
    fun indexPickedUris(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        launch("Index ${uris.size} selected file(s)") {
            processMixed(app, uris)
        }
    }

    /** Index all supported files directly under a SAF tree folder. */
    fun indexFolderTree(context: Context, treeChildren: List<Uri>, label: String) {
        val app = context.applicationContext
        launch(label) {
            processMixed(app, treeChildren)
        }
    }

    // ── Shared processing (pause / cancel / pacing) ─────────────────────────────

    private suspend fun processImages(app: Context, uris: List<Uri>) {
        val pipeline = IndexingPipeline.getInstance(app)
        setTotal(uris.size)
        runLoop(uris.size) { i ->
            val uri = uris[i]
            try {
                app.contentResolver.openInputStream(uri).use { stream ->
                    val bmp = stream?.let { BitmapFactory.decodeStream(it) }
                    if (bmp != null) {
                        pipeline.indexBitmap(bmp, uri.toString(), "dev_manual")
                        bmp.recycle()
                        bump(indexed = 1)
                    } else bump(skipped = 1)
                }
            } catch (e: Exception) {
                bump(failed = 1)
            }
        }
    }

    private suspend fun processMixed(app: Context, uris: List<Uri>) {
        val pipeline = IndexingPipeline.getInstance(app)
        val docIndexer = DocumentIndexer.getInstance(app)
        setTotal(uris.size)
        runLoop(uris.size) { i ->
            val uri = uris[i]
            val mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
            try {
                if (mime != null && mime.startsWith("image/")) {
                    app.contentResolver.openInputStream(uri).use { stream ->
                        val bmp = stream?.let { BitmapFactory.decodeStream(it) }
                        if (bmp != null) {
                            pipeline.indexBitmap(bmp, uri.toString(), "dev_manual")
                            bmp.recycle()
                            bump(indexed = 1)
                        } else bump(skipped = 1)
                    }
                } else {
                    // The indexer decides what it can read: a provider that reports a PDF as
                    // "application/octet-stream" (or reports nothing) still gives its name.
                    when (val result = docIndexer.indexDocument(uri, mime.orEmpty())) {
                        is IndexResult.Success -> bump(indexed = 1)
                        is IndexResult.Duplicate -> bump(skipped = 1)
                        is IndexResult.Failure ->
                            if (result.error is IndexError.UnsupportedFormat) bump(skipped = 1) else bump(failed = 1)
                    }
                }
            } catch (e: Exception) {
                bump(failed = 1)
            }
        }
    }

    /** The single paced, pausable, cancellable loop shared by every run. */
    private suspend inline fun runLoop(total: Int, crossinline indexOne: suspend (Int) -> Unit) {
        var i = 0
        while (i < total) {
            // Cooperative pause: hold between item boundaries without spinning the CPU.
            while (paused && !cancelled) delay(150)
            if (cancelled) break
            if (_status.value.phase == Phase.PAUSED) {
                _status.value = _status.value.copy(phase = Phase.RUNNING)
            }
            indexOne(i)
            i++
            _status.value = _status.value.copy(
                processed = i,
                durationMs = System.currentTimeMillis() - runStart,
            )
            // Same burst pacing as production bulk indexing.
            if (i % VaultConfig.Indexing.BATCH_SIZE == 0 && i < total) {
                delay(VaultConfig.Indexing.COOL_DOWN_MS)
            }
        }
    }

    // ── Status plumbing ─────────────────────────────────────────────────────────

    @Volatile private var runStart = 0L

    private fun launch(label: String, block: suspend () -> Unit) {
        if (isBusy) return
        paused = false
        cancelled = false
        runStart = System.currentTimeMillis()
        _status.value = Status(phase = Phase.RUNNING, label = label)
        job = scope.launch {
            try {
                block()
                _status.value = _status.value.copy(
                    phase = if (cancelled) Phase.CANCELLED else Phase.DONE,
                    durationMs = System.currentTimeMillis() - runStart,
                )
            } catch (e: Exception) {
                _status.value = _status.value.copy(
                    phase = Phase.ERROR,
                    durationMs = System.currentTimeMillis() - runStart,
                    note = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    private fun setTotal(total: Int) {
        _status.value = _status.value.copy(total = total)
    }

    private fun bump(indexed: Int = 0, skipped: Int = 0, failed: Int = 0) {
        val s = _status.value
        _status.value = s.copy(
            indexed = s.indexed + indexed,
            skipped = s.skipped + skipped,
            failed = s.failed + failed,
        )
    }
}
