package com.amar.vault.dev

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.amar.vault.LocalModel
import com.amar.vault.ModelManager
import com.amar.vault.VaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Developer-only helpers for loading local AI models from developer-selected folders.
 *
 * Reuses the existing [ModelManager] for all lifecycle (enable/disable/delete = load/unload/remove)
 * and the existing model directory contract (`filesDir/models/<fileName>`). The only new capability
 * is *importing* a `.gguf` a developer has placed on the device (e.g. via `adb push`) into that
 * managed directory and registering a [LocalModel] row for it — everything downstream (load into the
 * native engine, RAG, chat) is the untouched production path.
 */
object DevModelManager {

    private const val MODEL_DIR = "models"
    private val SUPPORTED_EXTS = listOf(".gguf")

    data class ScannedModel(val uri: Uri, val name: String, val sizeBytes: Long)

    /** last successful load durations by modelId (ms), for the dashboard. */
    private val _loadDurations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val loadDurations: StateFlow<Map<String, Long>> = _loadDurations.asStateFlow()

    // ── Scanning a developer-selected folder for model files ────────────────────

    suspend fun scanFolder(context: Context, treeUri: Uri): List<ScannedModel> =
        withContext(Dispatchers.IO) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            val out = mutableListOf<ScannedModel>()
            runCatching {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val docId = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        if (SUPPORTED_EXTS.any { name.lowercase().endsWith(it) }) {
                            out += ScannedModel(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, docId), name, size
                            )
                        }
                    }
                }
            }
            out.sortedBy { it.name }
        }

    /** Copy a scanned model into the managed directory and register a READY [LocalModel] row. */
    suspend fun importModel(context: Context, scanned: ScannedModel): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.filesDir, MODEL_DIR).apply { mkdirs() }
                val dest = File(dir, scanned.name)
                context.contentResolver.openInputStream(scanned.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Could not open ${scanned.name}")

                val modelId = "dev_${scanned.name}"
                val dao = VaultDatabase.get(context).localModelDao()
                dao.insert(
                    LocalModel(
                        modelId = modelId,
                        displayName = scanned.name.removeSuffix(".gguf"),
                        fileName = scanned.name,
                        downloadUrl = "",           // dev-imported: no remote source
                        sha256 = "",                // not verified for dev imports
                        sizeBytes = dest.length(),
                        requiredRamGb = 0f,
                        status = "READY",
                        downloadProgress = 100,
                        isEnabled = false,
                    )
                )
                modelId
            }
        }

    /**
     * Reconcile the managed directory with the DB: register any `.gguf` sitting in `filesDir/models`
     * that has no row yet (e.g. adb-pushed straight into the app sandbox). Non-destructive.
     */
    suspend fun refreshManaged(context: Context): Int = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) return@withContext 0
        val dao = VaultDatabase.get(context).localModelDao()
        val known = dao.getAllModels().map { it.fileName }.toHashSet()
        var added = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.lowercase().endsWith(".gguf") && f.name !in known) {
                dao.insert(
                    LocalModel(
                        modelId = "dev_${f.name}",
                        displayName = f.name.removeSuffix(".gguf"),
                        fileName = f.name,
                        downloadUrl = "",
                        sha256 = "",
                        sizeBytes = f.length(),
                        requiredRamGb = 0f,
                        status = "READY",
                        downloadProgress = 100,
                        isEnabled = false,
                    )
                )
                added++
            }
        }
        added
    }

    // ── Lifecycle (delegates to ModelManager) ───────────────────────────────────

    /** Load + select active; times the load and records it. Returns success. */
    suspend fun load(context: Context, modelId: String): Boolean {
        val start = System.currentTimeMillis()
        val ok = ModelManager.getInstance(context).enableModel(modelId)
        if (ok) {
            _loadDurations.value = _loadDurations.value + (modelId to (System.currentTimeMillis() - start))
        }
        return ok
    }

    suspend fun unload(context: Context, modelId: String) =
        ModelManager.getInstance(context).disableModel(modelId)

    suspend fun reload(context: Context, modelId: String): Boolean {
        ModelManager.getInstance(context).disableModel(modelId)
        return load(context, modelId)
    }

    /** Remove from the managed directory. Also drops dev-imported rows entirely (no re-download). */
    suspend fun remove(context: Context, model: LocalModel) = withContext(Dispatchers.IO) {
        ModelManager.getInstance(context).deleteModel(model.modelId) // disables + deletes the file
        if (model.downloadUrl.isBlank()) {
            // Dev-imported model has no remote source — a lingering PENDING row is useless, so drop it.
            VaultDatabase.get(context).localModelDao().deleteById(model.modelId)
            _loadDurations.value = _loadDurations.value - model.modelId
        }
    }

    fun modelPath(context: Context, fileName: String): String =
        File(File(context.filesDir, MODEL_DIR), fileName).absolutePath

    fun fileExists(context: Context, fileName: String): Boolean =
        File(File(context.filesDir, MODEL_DIR), fileName).exists()

    /** Best-effort quantization label parsed from a GGUF filename (e.g. "Q4_K_M"). */
    fun quantOf(fileName: String): String {
        val m = Regex("(Q\\d+(_[A-Z0-9]+)*|F16|F32|BF16)", RegexOption.IGNORE_CASE).find(fileName)
        return m?.value?.uppercase() ?: "—"
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes > 0 -> "%.0f KB".format(bytes / 1000.0)
        else -> "—"
    }
}
