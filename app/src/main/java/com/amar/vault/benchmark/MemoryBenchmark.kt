package com.amar.vault.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import com.amar.vault.VaultConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Module 8 — Memory benchmark.
 *
 * Sources (all real platform counters, sampled — nothing modeled):
 *  - Java heap: [Runtime] used/max;
 *  - native heap: [Debug.getNativeHeapAllocatedSize]/[Debug.getNativeHeapSize]
 *    (this is where hnswlib, BM25 and llama.cpp allocations live — reported as the
 *    JNI/native figure; per-library attribution is not available from the platform);
 *  - process PSS: [ActivityManager.getProcessMemoryInfo] (total / dalvik / native);
 *  - GC: `art.gc.gc-count` / `art.gc.gc-time` runtime stats (cumulative for process);
 *  - peak/average RAM: the [Sampler] polls Java+native usage every [SAMPLE_MS] while a
 *    benchmark run is in flight and reports the observed peak and mean.
 *
 * Bitmap allocations are not individually observable without instrumenting decode
 * sites (forbidden — framework only); they are contained in the Java-heap figures and
 * the metric says so explicitly.
 *
 * Embedding cache size is reported as the configured CAPACITY BOUND (entries × dim ×
 * 4 bytes) — labeled as a bound, not an occupancy measurement (the LRUs are private).
 */
class MemoryBenchmark(private val context: Context) {

    companion object {
        private const val SAMPLE_MS = 250L
        // Production LRU capacities: DefaultSemanticRetriever query cache (30) + passage cache (32).
        private const val QUERY_CACHE_ENTRIES = 30
        private const val PASSAGE_CACHE_ENTRIES = 32
    }

    /** Polls memory while the suite runs; started/stopped by the orchestrator. */
    class Sampler {
        private var job: Job? = null
        private val samples = mutableListOf<Long>()

        fun start(scope: CoroutineScope) {
            job = scope.launch(Dispatchers.Default) {
                while (isActive) {
                    val rt = Runtime.getRuntime()
                    val used = (rt.totalMemory() - rt.freeMemory()) + Debug.getNativeHeapAllocatedSize()
                    synchronized(samples) { samples.add(used) }
                    delay(SAMPLE_MS)
                }
            }
        }

        fun stop(): Pair<Double?, Double?> { // (peakBytes, avgBytes)
            job?.cancel()
            val snap = synchronized(samples) { samples.toList() }
            return (snap.maxOrNull()?.toDouble()) to (if (snap.isEmpty()) null else snap.average())
        }
    }

    fun run(sampler: Sampler?): BenchmarkSection {
        val rt = Runtime.getRuntime()
        val javaUsed = rt.totalMemory() - rt.freeMemory()
        val nativeUsed = Debug.getNativeHeapAllocatedSize()
        val nativeTotal = Debug.getNativeHeapSize()

        val pss = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
        } catch (e: Exception) { null }

        val gcCount = Debug.getRuntimeStat("art.gc.gc-count").toDoubleOrNull()
        val gcTime = Debug.getRuntimeStat("art.gc.gc-time").toDoubleOrNull()

        val (peak, avg) = sampler?.stop() ?: (null to null)
        val samplerNote = if (sampler == null) "no sampler attached to this run" else "sampled every ${SAMPLE_MS}ms during the benchmark run (Java+native heap)"

        val cacheBoundBytes =
            (QUERY_CACHE_ENTRIES + PASSAGE_CACHE_ENTRIES).toDouble() * VaultConfig.Embedding.DIM * 4

        return BenchmarkSection(
            id = "memory",
            title = "Memory (platform counters; peak/avg sampled during run)",
            metrics = listOf(
                MetricValue("ram.peak", peak, "bytes", false, samplerNote),
                MetricValue("ram.avg", avg, "bytes", false, samplerNote),
                MetricValue("javaHeap.used", javaUsed.toDouble(), "bytes", false),
                MetricValue("javaHeap.max", rt.maxMemory().toDouble(), "bytes", false, "heap ceiling (largeHeap manifest flag applies)"),
                MetricValue("nativeHeap.allocated", nativeUsed.toDouble(), "bytes", false,
                    "includes hnswlib + BM25 + llama.cpp + ONNX allocations (JNI/native memory)"),
                MetricValue("nativeHeap.size", nativeTotal.toDouble(), "bytes", false),
                MetricValue("pss.total", pss?.totalPss?.let { it * 1024.0 }, "bytes", false,
                    if (pss == null) "ActivityManager unavailable" else ""),
                MetricValue("pss.dalvik", pss?.dalvikPss?.let { it * 1024.0 }, "bytes", false),
                MetricValue("pss.native", pss?.nativePss?.let { it * 1024.0 }, "bytes", false),
                MetricValue("gc.count", gcCount, "count", false, "cumulative for this process lifetime"),
                MetricValue("gc.timeMs", gcTime, "ms", false, "cumulative for this process lifetime"),
                MetricValue("bitmapAllocations", null, "bytes", false,
                    "not individually observable without instrumenting decode sites; contained in javaHeap.used"),
                MetricValue("embeddingCache.capacityBound", cacheBoundBytes, "bytes", false,
                    "$QUERY_CACHE_ENTRIES query + $PASSAGE_CACHE_ENTRIES passage LRU entries × ${VaultConfig.Embedding.DIM} dim × 4B — capacity bound, not occupancy"),
            ),
        )
    }
}
