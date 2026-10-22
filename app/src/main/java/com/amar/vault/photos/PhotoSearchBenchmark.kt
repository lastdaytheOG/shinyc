package com.amar.vault.photos

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import com.amar.vault.VaultDatabase
import org.json.JSONObject
import java.io.File

/**
 * Times photo search on this phone, for the numbers a desktop cannot give: how long a query takes
 * to turn into a vector, how long a picture takes to load and to embed, how long the scan over
 * every stored picture takes, how much memory the models hold, and roughly how much charge
 * embedding pictures uses. Developer Tools runs it and shows [Report.text] to be read out.
 *
 * Nothing is written to the vault: pictures are embedded and the vectors thrown away.
 */
class PhotoSearchBenchmark(private val context: Context, private val photos: PhotoSearch) {

    data class Report(val text: String, val json: JSONObject)

    suspend fun run(): Report? {
        val (query, photo) = photos.encodersForBenchmark() ?: return null
        val db = VaultDatabase.get(context)
        val pictures = db.vaultDao().getAll().filter { it.itemType.isImage && it.parentDocumentId == null }
            .sortedByDescending { it.timestamp }.take(PICTURES)
        val heapBefore = Debug.getNativeHeapAllocatedSize()

        // Queries: the first runs warm the model up and are not counted.
        val queryMs = ArrayList<Double>()
        val scanMs = ArrayList<Double>()
        repeat(WARMUP) { query.encode(QUERIES[it % QUERIES.size]) }
        for (q in QUERIES) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            query.encode(q)
            val t1 = SystemClock.elapsedRealtimeNanos()
            photos.search(q, 24)
            val t2 = SystemClock.elapsedRealtimeNanos()
            queryMs += (t1 - t0) / 1e6
            // search() encodes the query again (cached nowhere), so the scan is the difference.
            scanMs += ((t2 - t1) / 1e6 - (t1 - t0) / 1e6).coerceAtLeast(0.0)
        }

        // Pictures: loading the thumbnail and embedding it, timed apart; charge read around it.
        val loadMs = ArrayList<Double>()
        val embedMs = ArrayList<Double>()
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val chargeBefore = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val embedStart = SystemClock.elapsedRealtime()
        for (p in pictures) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val bitmap = PhotoThumbnails.load(context, Uri.parse(p.uri)) ?: continue
            val t1 = SystemClock.elapsedRealtimeNanos()
            photo.encode(bitmap)
            val t2 = SystemClock.elapsedRealtimeNanos()
            bitmap.recycle()
            loadMs += (t1 - t0) / 1e6
            embedMs += (t2 - t1) / 1e6
        }
        val embedWall = SystemClock.elapsedRealtime() - embedStart
        val chargeAfter = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val heapAfter = Debug.getNativeHeapAllocatedSize()

        fun pct(xs: List<Double>, p: Double): Double =
            if (xs.isEmpty()) Double.NaN else xs.sorted()[((xs.size - 1) * p).toInt()]
        fun ms(x: Double) = if (x.isNaN()) "—" else "%.0f ms".format(x)
        val usedMicroAh = chargeBefore - chargeAfter
        val perPicture = if (embedMs.isNotEmpty() && usedMicroAh > 0) usedMicroAh / embedMs.size.toDouble() else Double.NaN

        val json = JSONObject()
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("soc", if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE)
            .put("model", photos.modelId)
            .put("pictures_in_index", photos.indexed)
            .put("query_p50_ms", pct(queryMs, 0.5)).put("query_p95_ms", pct(queryMs, 0.95))
            .put("scan_p50_ms", pct(scanMs, 0.5)).put("scan_p95_ms", pct(scanMs, 0.95))
            .put("thumbnail_p50_ms", pct(loadMs, 0.5)).put("thumbnail_p95_ms", pct(loadMs, 0.95))
            .put("embed_p50_ms", pct(embedMs, 0.5)).put("embed_p95_ms", pct(embedMs, 0.95))
            .put("pictures_timed", embedMs.size).put("embed_wall_ms", embedWall)
            .put("native_heap_mb", heapAfter / 1e6).put("native_heap_growth_mb", (heapAfter - heapBefore) / 1e6)
            .put("charge_used_uAh", usedMicroAh).put("charge_per_picture_uAh", perPicture)
        val text = buildString {
            appendLine("${json.getString("device")} · ${json.getString("soc")}")
            appendLine("model ${photos.modelId}, ${photos.indexed} pictures searchable")
            appendLine("query → vector   p50 ${ms(pct(queryMs, 0.5))}  p95 ${ms(pct(queryMs, 0.95))}  (${queryMs.size} queries)")
            appendLine("scan all photos  p50 ${ms(pct(scanMs, 0.5))}  p95 ${ms(pct(scanMs, 0.95))}")
            appendLine("thumbnail load   p50 ${ms(pct(loadMs, 0.5))}  p95 ${ms(pct(loadMs, 0.95))}")
            appendLine("photo → vector   p50 ${ms(pct(embedMs, 0.5))}  p95 ${ms(pct(embedMs, 0.95))}  (${embedMs.size} pictures)")
            appendLine("native memory    %.0f MB (models loaded)".format(heapAfter / 1e6))
            append("charge           ")
            append(if (perPicture.isNaN()) "too little to read (charging, or the counter is coarse)"
                   else "%.0f µAh for %d pictures, %.1f µAh each".format(usedMicroAh.toDouble(), embedMs.size, perPicture))
        }
        File(File(context.filesDir, PhotoModelFiles.DIR), "benchmark.json").apply { parentFile?.mkdirs() }.writeText(json.toString(1))
        return Report(text, json)
    }

    companion object {
        private const val WARMUP = 3
        private const val PICTURES = 40
        /** English, Hindi and Hindi in Latin letters, short and long, as people type them. */
        val QUERIES = listOf(
            "dog", "beach at sunset", "cat sitting in a bathroom sink", "birthday cake with candles",
            "kids playing cricket in the street", "red car parked next to a yellow building",
            "receipt", "temple with lamps at night", "two people on a scooter", "plate of food",
            "कुत्ता", "समुद्र तट पर सूर्यास्त", "बाथरूम के सिंक में बैठी बिल्ली", "शादी की सजावट",
            "नदी में हाथी को नहलाता आदमी", "खाने की थाली",
            "barf mein kutta", "beach par sunset", "shaadi ki decoration", "khane ki thali",
            "mele mein bada jhula", "purple phool wala good morning message",
        )
    }
}
