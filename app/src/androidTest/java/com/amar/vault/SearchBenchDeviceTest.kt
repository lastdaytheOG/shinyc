package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchDates
import com.amar.vault.benchmark.BenchRun
import com.amar.vault.benchmark.BenchVault
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.retrieval.RetrievalTuning
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SearchBenchDeviceTest {

    private val app by lazy { InstrumentationRegistry.getInstrumentation().targetContext.applicationContext }
    private val services by lazy { EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java) }
    private val dir by lazy { File(app.filesDir, "bench") }
    private val args by lazy { InstrumentationRegistry.getArguments() }

    private fun log(line: JSONObject) {
        android.util.Log.i("SearchBench", line.toString())
        dir.resolve("log.jsonl").appendText(line.toString() + "\n")
    }

    @Test
    fun load() = runBlocking {
        assumeTrue("run with -e bench load", args.getString("bench") == "load")
        val db = services.database()
        check(db.vaultDao().getAll().isEmpty()) { "the vault is not empty: clear the app's data first" }
        val photos = services.photoSearch().takeIf { args.getString("photos") != "off" }
        val loaded = BenchVault(db, services.bm25Index(), photos, BenchDates(anchor()))
            .load(dir.resolve("bench_corpus.jsonl"), dir.resolve("photos")) { n -> if (n % 100 == 0) android.util.Log.i("SearchBench", "loaded $n") }
        log(JSONObject().put("step", "load").put("documents", loaded.documents).put("rows", loaded.rows)
            .put("pictures", loaded.pictures).put("photoVectors", loaded.photoVectors).put("ms", loaded.ms).put("anchor", anchor()))
    }

    @Test
    fun vectors() = runBlocking {
        assumeTrue("run with -e bench vectors", args.getString("bench") == "vectors")
        val db = services.database()
        val search = services.photoSearch()
        val indexer = com.amar.vault.vectors.TextVectorIndexer(db, search)
        val stopAfter = args.getString("stopAfter")?.toInt() ?: Int.MAX_VALUE
        val t0 = System.currentTimeMillis()
        val one = indexer.run(1, maxRows = stopAfter)
        val two = if (one.remaining == 0 && one.embedded < stopAfter) indexer.run(2, maxRows = stopAfter - one.embedded) else null
        val model = search.textModelId!!
        val dao = db.textVectorDao()
        val stored = dao.hashesOf(model)
        val rows = dao.rows(stored.map { it.rowId }).associateBy { it.id }
        val wrongHash = stored.count { s -> rows[s.rowId]?.let { com.amar.vault.vectors.TextPassages.hash(it) != s.textHash } ?: true }
        val missing = dao.countWithout(model, 2, com.amar.vault.vectors.TextPassages.MIN_CHARS)
        val dupes = stored.size - stored.map { it.rowId to it.part }.toSet().size
        val bytes = db.openHelper.readableDatabase.query("SELECT COALESCE(SUM(length(vector) + length(rowId) + length(model) + length(textHash) + 24), 0) FROM text_vectors").use { c -> c.moveToFirst(); c.getLong(0) }
        log(JSONObject().put("step", "vectors").put("model", model)
            .put("tier1Embedded", one.embedded).put("tier1Ms", one.ms)
            .put("tier2Embedded", two?.embedded ?: 0).put("tier2Ms", two?.ms ?: 0)
            .put("vectors", stored.size).put("missing", missing).put("wrongHash", wrongHash).put("duplicates", dupes)
            .put("payloadBytesPerVector", if (stored.isEmpty()) 0 else bytes / stored.size)
            .put("ms", System.currentTimeMillis() - t0))
    }

    @Test
    fun scale() = runBlocking {
        assumeTrue("run with -e bench scale", args.getString("bench") == "scale")
        val target = args.getString("rows")?.toInt() ?: 10_000
        val db = services.database()
        val search = services.photoSearch()
        val model = search.textModelId!!
        val base = db.vaultDao().getAll().filter { it.ocrText.length >= 12 && !it.id.startsWith("scale-") }
        val vectors = db.textVectorDao().allOf(model).associateBy { it.rowId }
        var count = db.vaultDao().getAll().size
        var seed = 88172645463325252L
        fun word(): String {
            val sb = StringBuilder()
            repeat(7) {
                seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
                sb.append('a' + Math.floorMod(seed ushr 8, 26L).toInt())
            }
            return sb.toString()
        }
        val t0 = System.currentTimeMillis()
        var n = 0
        while (count < target) {
            val rows = ArrayList<VaultItem>()
            val vecs = ArrayList<com.amar.vault.vectors.TextVector>()
            repeat(minOf(500, target - count)) {
                val from = base[n % base.size]
                val row = from.copy(id = "scale-$n", ocrText = from.ocrText + " " + (1..4).joinToString(" ") { word() },
                    parentDocumentId = null, contentHash = "", itemType = if (from.itemType.isImage) from.itemType else ItemType.SCREENSHOT)
                rows += row
                vectors[from.id]?.let { v -> vecs += v.copy(rowId = row.id, textHash = com.amar.vault.vectors.TextPassages.hash(com.amar.vault.vectors.TextPassages.of(row))) }
                n++
            }
            db.vaultDao().insertAll(rows)
            db.textVectorDao().upsertAll(vecs)
            rows.forEach { services.bm25Index().addDocument(it.id, com.amar.vault.retrieval.KeywordText.of(it)) }
            count += rows.size
        }
        search.release()
        search.textIndex()
        val cases = BenchVault.cases(dir.resolve("bench_cases.jsonl"), BenchDates(anchor()))
            .filter { it.getString("slice") in setOf("exact", "paraphrase", "cross_language", "visual", "number_fact", "no_answer") }
            .let { all -> all.filterIndexed { i, _ -> i % maxOf(1, all.size / 200) == 0 } }
        val label = args.getString("label") ?: "scale-$target"
        val config = args.getString("config") ?: "designb"
        BenchRun(services.retrievalService()).run(cases, tuningFor(config), dir.resolve("results-$label.jsonl"), config)
        if (args.getString("also") != null) {
            val other = args.getString("also")!!
            BenchRun(services.retrievalService()).run(cases, tuningFor(other), dir.resolve("results-$label-$other.jsonl"), other)
        }
        log(JSONObject().put("step", "scale").put("rows", count).put("textVectors", search.textIndex()?.size ?: 0)
            .put("buildMs", System.currentTimeMillis() - t0).put("cases", cases.size).put("label", label))
    }

    @Test
    fun unscale() = runBlocking {
        assumeTrue("run with -e bench unscale", args.getString("bench") == "unscale")
        val db = services.database()
        val ids = db.vaultDao().getAll().map { it.id }.filter { it.startsWith("scale-") }
        ids.chunked(500).forEach { some ->
            db.textVectorDao().deleteRows(some)
            db.factDao().deleteRows(some)
            db.coverageDao().deleteItems(some)
        }
        db.openHelper.writableDatabase.execSQL("DELETE FROM vault_items WHERE id LIKE 'scale-%'")
        services.bm25Index().removeDocuments(ids)
        services.photoSearch().forgetRows(ids)
        log(JSONObject().put("step", "unscale").put("removed", ids.size).put("rows", db.vaultDao().getAll().size))
    }

    @Test
    fun probe() = runBlocking {
        assumeTrue("run with -e bench probe", args.getString("bench") == "probe")
        val q = args.getString("q")!!.replace('_', ' ')
        val lex = services.lexicalRetriever()
        lex.awaitReady()
        val out = JSONObject().put("q", q)
            .put("bm25", org.json.JSONArray(lex.bm25(q, 300)))
            .put("fts", org.json.JSONArray(lex.fts(q, 50)))
        q.split(' ').forEach { w -> out.put("bm25:$w", org.json.JSONArray(lex.bm25(w, 20))) }
        for (mode in listOf("designb", "designb_bounded")) {
            var trace: com.amar.vault.retrieval.RetrievalTrace? = null
            val req = com.amar.vault.retrieval.RetrievalRequest.forResultList(q)
            val result = services.retrievalService().retrieve(req.copy(tuning = tuningFor(mode).copy(relaxEmptyFilter = true), traceSink = { trace = it }))
            out.put(mode, JSONObject().put("items", org.json.JSONArray(result.items.take(8).map { it.id }))
                .put("lanes", JSONObject(trace?.lanes?.associate { it.lane to it.candidateIds.take(8) } ?: emptyMap<String, Any>())))
        }
        log(out)
    }

    @Test
    fun run() = runBlocking {
        assumeTrue("run with -e bench run", args.getString("bench") == "run")
        val label = args.getString("label") ?: "run"
        val config = args.getString("config") ?: "today"
        val tuning = tuningFor(config)
        val cases = BenchVault.cases(dir.resolve("bench_cases.jsonl"), BenchDates(anchor()))
            .filter { c -> args.getString("slice")?.split(',')?.contains(c.getString("slice")) ?: true }
        val t0 = System.currentTimeMillis()
        BenchRun(services.retrievalService()).run(cases, tuning, dir.resolve("results-$label.jsonl"), config)
        log(JSONObject().put("step", "run").put("label", label).put("config", config).put("cases", cases.size)
            .put("ms", System.currentTimeMillis() - t0))
    }

    private fun anchor(): Long {
        val f = dir.resolve("anchor.txt")
        if (!f.exists()) f.writeText(System.currentTimeMillis().toString())
        return f.readText().trim().toLong()
    }

    private fun tuningFor(config: String): RetrievalTuning = when (config) {
        "today", "designb" -> RetrievalTuning()
        "keyword" -> RetrievalTuning(semanticEnabled = false, photoLane = false)
        "nomeaning" -> RetrievalTuning(semanticEnabled = false)
        "bounded" -> RetrievalTuning(candidateBounded = true)
        "designb_bounded" -> RetrievalTuning(candidateBounded = true)
        "nophoto" -> RetrievalTuning(photoLane = false)
        "bounded_nomodel" -> RetrievalTuning(candidateBounded = true, semanticEnabled = false, photoLane = false)
        "nomodel" -> RetrievalTuning(semanticEnabled = false, photoLane = false)
        "nobridge" -> RetrievalTuning(hinglishBridge = false)
        "hlold" -> RetrievalTuning(hinglishSaid = false)
        "hlrules" -> RetrievalTuning(hinglishModel = false)
        "hlnolanes" -> RetrievalTuning(hinglishLanes = false)
        else -> error("unknown config $config")
    }
}
