package com.amar.vault.benchmark

import android.content.Context
import com.amar.vault.AppEmbeddingEngine
import com.amar.vault.EmbeddingManifest
import com.amar.vault.VaultConfig
import com.amar.vault.VectorSearchManager
import com.amar.vault.retrieval.SemanticRetriever
import java.io.File

/**
 * Module 5 — Embedding benchmark.
 *
 * Measures the production embedding stack through its public seams:
 *  - generation latency: fixed multilingual probe texts through the real
 *    [AppEmbeddingEngine] (avg / median / p95 over PROBE_ROUNDS × probes);
 *  - query-cache effect: cold vs warm latency of the same query through the real
 *    [SemanticRetriever.embedQuery] (its internal LRU is intentionally private, so the
 *    cache is observed behaviourally — a warm repeat that costs ~0 ms IS the hit);
 *  - storage: on-disk bytes of the vector mappings + embedding manifest, plus the
 *    indexed-vector count (native HNSW file sizes are covered by the storage module);
 *  - identity: dimensions, model id/version/schema from [VaultConfig.Embedding];
 *  - migration readiness: manifest present AND matching the running identity.
 */
class EmbeddingBenchmark(
    private val context: Context,
    private val vectorSearch: VectorSearchManager,
    private val semantic: SemanticRetriever,
) {

    companion object {
        // Fixed probes — stable across runs so latency is comparable run-to-run.
        private val PROBES = listOf(
            "coffee receipt from march with total amount",
            "flight ticket booking confirmation pnr",
            "मैंने पिछले महीने कितना खर्च किया",
            "screenshot of a whatsapp conversation about travel plans",
            "insurance policy renewal document",
        )
        private const val PROBE_ROUNDS = 3
    }

    suspend fun run(): BenchmarkSection {
        val notes = mutableListOf<String>()

        // ── Generation latency (real engine, real tokenizer) ─────────────────
        val latencies = mutableListOf<Long>()
        var dimSeen: Int? = null
        var engineError: String? = null
        try {
            // Untimed warm-up: first call pays one-time model/session init.
            AppEmbeddingEngine.embedPassage(context, PROBES.first())
            repeat(PROBE_ROUNDS) {
                for (probe in PROBES) {
                    val t0 = System.nanoTime()
                    val vec = AppEmbeddingEngine.embedPassage(context, probe)
                    latencies.add((System.nanoTime() - t0) / 1_000_000)
                    dimSeen = vec.size
                }
            }
        } catch (e: Exception) {
            engineError = "embedding engine unavailable: ${e.message}"
        }
        val sorted = latencies.sorted()

        // ── Query-cache behaviour (cold vs warm through the production retriever) ──
        var coldMs: Double? = null
        var warmMs: Double? = null
        if (engineError == null) {
            try {
                val probe = "benchmark cache probe ${System.currentTimeMillis()}"
                val t0 = System.nanoTime()
                semantic.embedQuery(probe)
                coldMs = (System.nanoTime() - t0) / 1_000_000.0
                val t1 = System.nanoTime()
                semantic.embedQuery(probe)
                warmMs = (System.nanoTime() - t1) / 1_000_000.0
            } catch (e: Exception) {
                notes.add("cache probe failed: ${e.message}")
            }
        }

        // ── Storage + identity ────────────────────────────────────────────────
        val mappingsBytes = File(context.filesDir, VaultConfig.Vector.MAPPINGS_FILENAME).takeIf { it.exists() }?.length()
        val manifestFile = File(context.filesDir, VaultConfig.Vector.EMBEDDING_MANIFEST_FILENAME)
        val manifest = EmbeddingManifest.load(manifestFile)
        val migrationReady = manifest != null && manifest.matchesCurrent()

        val err = engineError ?: ""
        return BenchmarkSection(
            id = "embedding",
            title = "Embedding generation, cache, storage & identity",
            metrics = listOf(
                MetricValue("generation.avg", BenchmarkMath.mean(latencies), "ms", false, err),
                MetricValue("generation.median", BenchmarkMath.median(sorted), "ms", false, err),
                MetricValue("generation.p95", BenchmarkMath.percentile(sorted, 0.95), "ms", false, err),
                MetricValue("generation.samples", latencies.size.toDouble(), "count", true),
                MetricValue("queryCache.coldMs", coldMs, "ms", false,
                    if (coldMs == null) "cache probe unavailable" else "first embed of a novel query"),
                MetricValue("queryCache.warmMs", warmMs, "ms", false,
                    if (warmMs == null) "cache probe unavailable" else "repeat embed — LRU hit path; hit rate itself is not instrumented in production code"),
                MetricValue("vectors.indexed", vectorSearch.getIndexedCount().toDouble(), "count", true),
                MetricValue("storage.mappingsBytes", mappingsBytes?.toDouble(), "bytes", false,
                    if (mappingsBytes == null) "no mappings file yet" else ""),
                MetricValue("dimensions", (dimSeen ?: VaultConfig.Embedding.DIM).toDouble(), "dim", true,
                    if (dimSeen == null) "from VaultConfig (engine not exercised)" else "observed from live engine output"),
                MetricValue("model.schemaVersion", VaultConfig.Embedding.SCHEMA_VERSION.toDouble(), "version", true,
                    "modelId=${VaultConfig.Embedding.MODEL_ID} modelVersion=${VaultConfig.Embedding.MODEL_VERSION}"),
                MetricValue("migrationReady", if (migrationReady) 1.0 else 0.0, "bool", true,
                    if (manifest == null) "embedding_manifest.json absent — created on next vector-store init"
                    else if (!migrationReady) "manifest identity differs from running model — re-embed migration required"
                    else "manifest present and matches running model"),
            ),
            rows = emptyList(),
        )
    }
}
