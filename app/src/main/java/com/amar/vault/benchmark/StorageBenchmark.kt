package com.amar.vault.benchmark

import android.content.Context
import com.amar.vault.VaultConfig
import com.amar.vault.VaultDatabase
import java.io.File

/**
 * Module 9 — Storage benchmark.
 *
 * Measures real on-disk bytes:
 *  - Room database (`vault.db` + `-wal` + `-shm`);
 *  - OCR text bytes and metadata rows *inside* the database (via SQL aggregate over
 *    the live tables — OCR "storage" is a DB column, not a separate file);
 *  - embedding storage: vector mappings + manifest + the native index files in
 *    `filesDir` that are not attributable to another known owner;
 *  - AI model files (`.onnx`, `.gguf`, tokenizer assets copied to filesDir).
 *
 * Scale figures: `bytesPerDocument.*` are measured (current bytes ÷ current corpus
 * size). The `projected.*` values are that measured per-document figure × 1k/10k/100k,
 * explicitly labeled linear projections from measured data — the framework never
 * pretends they were observed at that scale.
 */
class StorageBenchmark(
    private val context: Context,
    private val db: VaultDatabase,
) {

    fun run(): BenchmarkSection {
        val filesDir = context.filesDir

        // ── Database files ────────────────────────────────────────────────────
        val dbFile = context.getDatabasePath("vault.db")
        val dbBytes = listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm"))
            .filter { it.exists() }.sumOf { it.length() }

        // ── In-database aggregates (live SQL, read-only) ─────────────────────
        var docCount: Long? = null
        var ocrBytes: Long? = null
        var metadataRows: Long? = null
        var sqlNote = ""
        try {
            val sql = db.openHelper.readableDatabase
            sql.query("SELECT COUNT(*), IFNULL(SUM(LENGTH(ocrText)), 0) FROM vault_items").use { c ->
                if (c.moveToFirst()) { docCount = c.getLong(0); ocrBytes = c.getLong(1) }
            }
            sql.query("SELECT COUNT(*) FROM vault_metadata").use { c ->
                if (c.moveToFirst()) metadataRows = c.getLong(0)
            }
        } catch (e: Exception) {
            sqlNote = "DB aggregate query failed: ${e.message}"
        }

        // ── filesDir classification ───────────────────────────────────────────
        val mappingsBytes = File(filesDir, VaultConfig.Vector.MAPPINGS_FILENAME).lengthOrZero()
        val manifestBytes = File(filesDir, VaultConfig.Vector.EMBEDDING_MANIFEST_FILENAME).lengthOrZero()
        var modelBytes = 0L
        var otherBinaryBytes = 0L // native HNSW index files land here (name owned by JNI layer)
        filesDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            when {
                f.name == VaultConfig.Vector.MAPPINGS_FILENAME -> {}
                f.name == VaultConfig.Vector.EMBEDDING_MANIFEST_FILENAME -> {}
                f.extension.lowercase() in setOf("onnx", "gguf", "model") || f.name.startsWith("bge_") ->
                    modelBytes += f.length()
                else -> otherBinaryBytes += f.length()
            }
        }

        // ── Scale: measured per-doc + labeled linear projections ─────────────
        val contentBytes = dbBytes + mappingsBytes + manifestBytes + otherBinaryBytes // models excluded: size is corpus-independent
        val docs = docCount ?: 0
        val perDoc = if (docs > 0) contentBytes.toDouble() / docs else null
        val perDocNote = if (perDoc == null) "corpus empty — index documents first" else "measured: content bytes ÷ $docs documents"
        fun projection(n: Int) = MetricValue(
            "projected.${n}kDocs", perDoc?.let { it * n * 1000 }, "bytes", false,
            if (perDoc == null) perDocNote else "LINEAR PROJECTION from measured bytesPerDocument — not observed at this scale",
        )

        return BenchmarkSection(
            id = "storage",
            title = "Storage (measured on-disk bytes + labeled projections)",
            metrics = listOf(
                MetricValue("db.bytes", dbBytes.toDouble(), "bytes", false, "vault.db + wal + shm"),
                MetricValue("db.documents", docCount?.toDouble(), "count", true, sqlNote),
                MetricValue("ocr.textBytes", ocrBytes?.toDouble(), "bytes", false,
                    sqlNote.ifBlank { "SUM(LENGTH(ocrText)) — OCR text lives inside vault.db" }),
                MetricValue("metadata.rows", metadataRows?.toDouble(), "count", false, sqlNote),
                MetricValue("vectorMappings.bytes", mappingsBytes.toDouble(), "bytes", false),
                MetricValue("embeddingManifest.bytes", manifestBytes.toDouble(), "bytes", false),
                MetricValue("nativeIndexes.bytes", otherBinaryBytes.toDouble(), "bytes", false,
                    "unclassified filesDir files — contains the native HNSW index; BM25 keeps no files (in-memory, rebuilt from Room)"),
                MetricValue("models.bytes", modelBytes.toDouble(), "bytes", false, "corpus-independent (excluded from per-doc figure)"),
                MetricValue("bytesPerDocument", perDoc, "bytes/doc", false, perDocNote),
                projection(1), projection(10), projection(100),
            ),
        )
    }

    private fun File.lengthOrZero(): Long = if (exists()) length() else 0L
}
