package com.amar.vault.benchmark

import android.content.Context
import android.os.Debug
import android.os.Process
import com.amar.vault.photos.GemmaTokenizer
import com.amar.vault.photos.PhotoIndex
import com.amar.vault.photos.PhotoModelFiles
import com.amar.vault.photos.PhotoVectors
import com.amar.vault.photos.QueryEncoder
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

object TextEncoderBenchmark {

    val LENGTHS = listOf(64, 128, 256, 384)
    val SCAN_COUNTS = listOf(10_000, 50_000, 100_000)

    private const val SAMPLE = "The graphics processor splits a frame into tiles and runs thousands of small threads at once. " +
        "Each streaming multiprocessor holds its own register file, shared memory and warp schedulers, " +
        "so memory bandwidth rather than arithmetic often decides how fast a kernel finishes. " +
        "बिजली का बिल हर महीने की पाँच तारीख़ तक जमा करना होता है, वरना देर से भुगतान का शुल्क लगता है। "

    fun encoder(context: Context, threads: Int = 4): Pair<QueryEncoder, Long>? {
        val files = PhotoModelFiles(context)
        if (!files.installFromApk()) return null
        val t0 = System.nanoTime()
        val tokenizer = files.tokenizer().inputStream().use { GemmaTokenizer.read(it) }
        val encoder = QueryEncoder(files.queryModel().path, tokenizer, threads)
        return encoder to (System.nanoTime() - t0) / 1_000_000
    }

    fun measureEncoder(context: Context, runs: Int = 10, threads: Int = 4): JSONObject {
        val out = JSONObject().put("threads", threads).put("runs", runs)
        val before = memory()
        out.put("memoryBefore", before)
        val (encoder, loadMs) = encoder(context, threads) ?: return out.put("error", "model pack not on this device")
        encoder.use { enc ->
            out.put("coldLoadMs", loadMs)
            out.put("memoryAfterLoad", memory())
            val longText = SAMPLE.repeat(30)
            val lengths = JSONObject()
            var firstDone = false
            for (n in LENGTHS) {
                val ids = enc.passageIds("guide.pdf", longText, n)
                val cpu0 = Process.getElapsedCpuTime()
                val w0 = System.nanoTime()
                enc.encodeIds(ids)
                val first = (System.nanoTime() - w0) / 1e6
                if (!firstDone) { out.put("firstInferenceMs", first); firstDone = true }
                val times = ArrayList<Double>()
                val wallStart = System.nanoTime()
                repeat(runs) {
                    val t = System.nanoTime()
                    enc.encodeIds(ids)
                    times.add((System.nanoTime() - t) / 1e6)
                }
                val wall = (System.nanoTime() - wallStart) / 1e6
                val cpu = (Process.getElapsedCpuTime() - cpu0).toDouble()
                times.sort()
                lengths.put(n.toString(), JSONObject()
                    .put("tokens", ids.size)
                    .put("firstMs", round(first))
                    .put("medianMs", round(times[times.size / 2]))
                    .put("p95Ms", round(times[((times.size - 1) * 0.95).toInt()]))
                    .put("minMs", round(times.first()))
                    .put("maxMs", round(times.last()))
                    .put("coresBusy", round(cpu / (wall + first))))
            }
            out.put("lengths", lengths)
            val q = ArrayList<Double>()
            repeat(runs) {
                val t = System.nanoTime()
                enc.encode("receipt for the new phone charger")
                q.add((System.nanoTime() - t) / 1e6)
            }
            q.sort()
            out.put("queryMedianMs", round(q[q.size / 2])).put("queryP95Ms", round(q[((q.size - 1) * 0.95).toInt()]))
            out.put("memoryAfterRuns", memory())
        }
        return out
    }

    fun measureScan(counts: List<Int> = SCAN_COUNTS, dims: List<Int> = listOf(768, 256), runs: Int = 21): JSONArray {
        val results = JSONArray()
        val random = Random(7)
        for (dim in dims) for (n in counts) {
            val index = PhotoIndex(dim)
            val bytes = ByteArray(dim)
            for (i in 0 until n) {
                for (j in 0 until dim) bytes[j] = (random.nextInt(255) - 127).toByte()
                index.put("r$i", bytes, 0.001f)
            }
            val query = PhotoVectors.normalized(FloatArray(dim) { random.nextFloat() - 0.5f })
            index.top(query, 50)
            val times = ArrayList<Double>()
            repeat(runs) {
                val t = System.nanoTime()
                index.top(query, 50)
                times.add((System.nanoTime() - t) / 1e6)
            }
            times.sort()
            results.put(JSONObject().put("dim", dim).put("count", n)
                .put("medianMs", round(times[times.size / 2]))
                .put("p95Ms", round(times[((times.size - 1) * 0.95).toInt()])))
        }
        return results
    }

    fun section(context: Context): BenchmarkSection {
        val e = measureEncoder(context, runs = 5)
        if (e.has("error")) return BenchmarkSection("embedding", "Text model", listOf(MetricValue("available", 0.0, "bool", true, e.getString("error"))))
        val lengths = e.getJSONObject("lengths")
        val metrics = mutableListOf(
            MetricValue("coldLoad", e.optDouble("coldLoadMs"), "ms", false),
            MetricValue("query.median", e.optDouble("queryMedianMs"), "ms", false),
            MetricValue("query.p95", e.optDouble("queryP95Ms"), "ms", false),
        )
        for (n in LENGTHS) lengths.optJSONObject(n.toString())?.let {
            metrics += MetricValue("piece$n.median", it.optDouble("medianMs"), "ms", false)
        }
        return BenchmarkSection("embedding", "Text model (EmbeddingGemma 2, 4 threads)", metrics)
    }

    private fun memory(): JSONObject {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return JSONObject()
            .put("totalPssKb", info.totalPss)
            .put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
            .put("javaHeapKb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024)
    }

    private fun round(x: Double): Double = Math.round(x * 10) / 10.0
}
