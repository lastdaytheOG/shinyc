package com.amar.vault.benchmark

import android.content.Context
import android.os.Build
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import com.amar.vault.VectorSearchManager
import com.amar.vault.retrieval.Bm25Index
import com.amar.vault.retrieval.LexicalRetriever
import com.amar.vault.retrieval.RetrievalService
import com.amar.vault.retrieval.SearchRepository
import com.amar.vault.retrieval.SemanticRetriever
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Module 2 — Benchmark orchestrator (the permanent evaluation entry point).
 *
 * Composes the module benchmarks over the golden dataset, wraps the whole run in a
 * memory sampler + battery session, appends a regression comparison against the
 * pinned baseline, and persists JSON/CSV/MD + history via [ReportStore].
 *
 * Suites:
 *  - FULL         → everything below
 *  - OCR          → ocr
 *  - RETRIEVAL    → retrieval.quality
 *  - EMBEDDING    → embedding
 *  - PERFORMANCE  → retrieval.performance + indexing + memory + storage
 *
 * Every value in the report is measured during the run (or explicitly null with a
 * reason). This class never writes user data; its only side effects are report files
 * under `benchmark-results/` and the documented transient probes in [IndexingBenchmark].
 */
class BenchmarkRunner private constructor(
    private val context: Context,
    private val retrieval: RetrievalService,
    private val repository: SearchRepository,
    private val lexical: LexicalRetriever,
    private val semantic: SemanticRetriever,
    private val bm25: Bm25Index,
    private val db: VaultDatabase,
    private val vectorSearch: VectorSearchManager,
) {

    companion object {
        /** Resolve every dependency from the existing Hilt graph — no new instances of production services. */
        fun create(context: Context): BenchmarkRunner {
            val ep = EntryPointAccessors.fromApplication(
                context.applicationContext, BenchmarkEntryPoint::class.java
            )
            return BenchmarkRunner(
                context.applicationContext,
                ep.retrievalService(), ep.searchRepository(), ep.lexicalRetriever(),
                ep.semanticRetriever(), ep.bm25Index(), ep.database(), ep.vectorSearchManager(),
            )
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BenchmarkEntryPoint {
        fun retrievalService(): RetrievalService
        fun searchRepository(): SearchRepository
        fun lexicalRetriever(): LexicalRetriever
        fun semanticRetriever(): SemanticRetriever
        fun bm25Index(): Bm25Index
        fun database(): VaultDatabase
        fun vectorSearchManager(): VectorSearchManager
    }

    private val datasetStore = GoldenDatasetStore(context)
    private val reportStore = ReportStore(context)
    private val battery = BatteryBenchmark(context)
    private val memory = MemoryBenchmark(context)
    private val regression = RegressionRunner()

    val reports: ReportStore get() = reportStore

    suspend fun run(
        suite: BenchmarkSuite,
        scope: CoroutineScope,
        onProgress: (String) -> Unit = {},
    ): BenchmarkRunReport = withContext(Dispatchers.IO) {
        val runId = reportStore.newRunId(suite)
        val startedAt = System.currentTimeMillis()
        val cases = datasetStore.allCases()
        val notes = mutableListOf<String>()
        if (cases.isEmpty()) notes.add("golden dataset is empty — add cases (see docs/BENCHMARKS.md)")

        val sampler = MemoryBenchmark.Sampler().also { it.start(scope) }
        val sections = mutableListOf<BenchmarkSection>()
        val batterySessions = mutableListOf<BatteryBenchmark.SessionResult>()

        suspend fun runModule(label: String, enabled: Boolean, block: suspend () -> BenchmarkSection) {
            if (!enabled) return
            onProgress(label)
            val (section, batteryResult) = battery.session(label) {
                try {
                    block()
                } catch (e: Exception) {
                    VaultLog.w("BenchmarkRunner", "$label failed: ${e.message}")
                    BenchmarkSection(
                        id = label.lowercase().replace(' ', '.'),
                        title = label,
                        metrics = listOf(MetricValue("module.error", null, "", false, "module failed: ${e.message}")),
                    )
                }
            }
            sections.add(section)
            batterySessions.add(batteryResult)
        }

        val full = suite == BenchmarkSuite.FULL
        val perf = suite == BenchmarkSuite.PERFORMANCE
        runModule("Retrieval quality", full || suite == BenchmarkSuite.RETRIEVAL) {
            RetrievalEvaluator(retrieval, repository).evaluate(cases)
        }
        // Sprint 4B: acronym equivalence rides the retrieval suite (self-contained pairs,
        // no golden dataset dependency).
        runModule("Acronym equivalence", full || suite == BenchmarkSuite.RETRIEVAL) {
            AcronymBenchmark(retrieval, repository).run()
        }
        runModule("OCR", full || suite == BenchmarkSuite.OCR) {
            OcrBenchmark(context, datasetStore).run(cases)
        }
        // Sprint E1: per-strategy contribution/latency of the OCR ensemble (evaluation only).
        runModule("OCR contribution", full || suite == BenchmarkSuite.OCR) {
            OcrContributionBenchmark(context, datasetStore).run(cases)
        }
        // Sprint E2: leave-one-out ablation of the OCR ensemble + escalation-ladder simulation
        // (offline merge replay over recorded strategy texts — production OCR untouched).
        runModule("OCR ablation", full || suite == BenchmarkSuite.OCR) {
            OcrAblationBenchmark(context, datasetStore).run(cases)
        }
        runModule("Embedding", full || suite == BenchmarkSuite.EMBEDDING) {
            EmbeddingBenchmark(context, vectorSearch, semantic).run()
        }
        runModule("Retrieval performance", full || perf) {
            PerformanceBenchmark(retrieval, lexical, semantic, repository).run(cases)
        }
        runModule("Indexing", full || perf) {
            IndexingBenchmark(context, db, bm25).run()
        }
        // Sprint E3: slowest-document/page profiler report (captured production profiles +
        // read-only extraction probe over pushed documents; measurements only).
        runModule("Indexing profiler", full || perf) {
            IndexingProfilerBenchmark(context).run()
        }
        runModule("Storage", full || perf) {
            StorageBenchmark(context, db).run()
        }

        // Memory last: stops the sampler so peak/avg cover the whole run.
        onProgress("Memory")
        sections.add(memory.run(sampler))
        sections.add(battery.toSection(batterySessions))

        // Regression vs pinned baseline (Module 11).
        val baseline = reportStore.readBaseline()
        val corpusCount = runCatching {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM vault_items").use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
        }.getOrDefault(0L)

        var report = BenchmarkRunReport(
            runId = runId,
            suite = suite,
            startedAtMs = startedAt,
            durationMs = System.currentTimeMillis() - startedAt,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            sdkInt = Build.VERSION.SDK_INT,
            corpusDocumentCount = corpusCount,
            sections = sections,
            notes = notes,
        )
        val findings = baseline?.let { regression.compare(it, report) } ?: emptyList()
        report = report.copy(sections = sections + regression.toSection(baseline?.runId, findings))

        onProgress("Writing reports")
        reportStore.write(report)
        report
    }
}
