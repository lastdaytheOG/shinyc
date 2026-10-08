package com.amar.vault

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.retrieval.KeywordIndexFill
import com.amar.vault.retrieval.KeywordText
import com.amar.vault.retrieval.NativeBm25Index
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Times what the app does to the keyword engine when it starts ([KeywordIndexFill]: read every
 * row from the database and hand its text to the engine) and how long the engine then takes
 * to answer — for the vault on the device or for a made-up one of any size.
 *
 * It asserts nothing about the numbers. It is run on request only:
 *
 *     adb shell am instrument -w -e class com.amar.vault.KeywordIndexMeasureDeviceTest \
 *         -e keyword measure [-e rows 50000] com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * With `rows`, a vault of that many rows is made in a database of its own (deleted afterwards)
 * out of the pages stored on the device, each with a few words no other row has, so that the
 * number of different words grows with the vault as it does in a real one. Everything is done
 * in an engine of this test's own: neither the app's vault nor its engine is touched.
 *
 * One line per run is added to `keyword-measure/result.jsonl` in the app's files. The numbers
 * this gave on 2026-10-08, before and after the engine was rewritten, are in
 * [KeywordIndexFill]'s note.
 */
@RunWith(AndroidJUnit4::class)
class KeywordIndexMeasureDeviceTest {

    private val app by lazy { InstrumentationRegistry.getInstrumentation().targetContext.applicationContext }

    @Test
    fun measure() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e keyword measure", args.getString("keyword") == "measure")
        val rows = args.getString("rows")?.toInt() ?: 0
        val label = args.getString("label") ?: "run"

        val vault = VaultDatabase.get(app)
        val real = vault.vaultDao().getAll()
        assertTrue("nothing is indexed on this device", real.isNotEmpty())
        // Let the app finish its own start-up work first, so that it is not timed alongside.
        Thread.sleep(5_000)

        val scaleDb = if (rows > 0) madeUpVault(real, rows) else null
        val db = scaleDb ?: vault
        val out = File(app.filesDir, "keyword-measure").apply { mkdirs() }.resolve("result.jsonl")
        try {
            repeat(3) { run ->
                val index = NativeBm25Index()
                val report = KeywordIndexFill(db, index).fill()

                // Where the indexing time goes: putting a row's text together, and the engine.
                val data = db.vaultDao().getAllSearchableData()
                val t0 = System.nanoTime()
                val rest = KeywordText.ForManyRows().let { many -> data.map { many.besidesTheText(it) } }
                val t1 = System.nanoTime()
                val again = NativeBm25Index()
                data.forEachIndexed { i, row -> again.addDocument(row.id, row.ocrText, rest[i]) }
                val t2 = System.nanoTime()
                again.shutdown()
                val texts = data.map { it.ocrText }

                val words = someWords(texts)
                val known = timeQueries(words) { index.search(it) }
                val parts = timeQueries(words.map { it.dropLast(2) }) { index.search(it) }
                val typos = timeQueries(words.map { it.substring(0, 2) + it[3] + it[2] + it.substring(4) }) { index.search(it) }
                val phrases = timeQueries(words.windowed(3, 3).map { it.joinToString(" ") }) { index.search(it) }

                val size = index.size()
                val line = JSONObject().apply {
                    put("label", label); put("run", run); put("rows", report.items)
                    put("chars", texts.sumOf { it.length.toLong() }); put("differentWords", size.words)
                    put("fillMs", report.totalMs); put("readMs", report.readMs); put("indexMs", report.indexMs)
                    put("ofIndexingTextMs", ms(t1 - t0)); put("ofIndexingEngineMs", ms(t2 - t1))
                    put("knownWordMs", known.first); put("knownWordMaxMs", known.second)
                    put("partOfWordMs", parts.first); put("partOfWordMaxMs", parts.second)
                    put("typoMs", typos.first); put("typoMaxMs", typos.second)
                    put("threeWordsMs", phrases.first); put("threeWordsMaxMs", phrases.second)
                }.toString()
                android.util.Log.i("KeywordMeasure", line)
                out.appendText(line + "\n")
                index.shutdown()
            }
        } finally {
            scaleDb?.close()
            app.deleteDatabase(SCALE_DB)
        }
    }

    private fun ms(nanos: Long): Double = nanos / 10_000 / 100.0

    /** Average and longest time of [search] over [queries], in milliseconds. */
    private fun timeQueries(queries: List<String>, search: (String) -> Any): Pair<Double, Double> {
        var total = 0L
        var longest = 0L
        for (q in queries) {
            val t = System.nanoTime()
            search(q)
            val took = System.nanoTime() - t
            total += took
            if (took > longest) longest = took
        }
        return ms(total / queries.size.coerceAtLeast(1)) to ms(longest)
    }

    /** 120 plain words of six letters or more, spread over everything that is indexed. */
    private fun someWords(texts: List<String>): List<String> {
        val all = texts.asSequence().flatMap { WORD.findAll(it.lowercase()).map { m -> m.value } }
            .filter { it.length >= 6 && it.all { c -> c in 'a'..'z' } }.toSortedSet().toList()
        val step = (all.size / 120).coerceAtLeast(1)
        return all.filterIndexed { i, _ -> i % step == 0 }.take(120)
    }

    private suspend fun madeUpVault(real: List<VaultItem>, rows: Int): VaultDatabase {
        app.deleteDatabase(SCALE_DB)
        val db = Room.databaseBuilder(app, VaultDatabase::class.java, SCALE_DB).build()
        var seed = 88172645463325252L
        fun nextWord(): String {
            val sb = StringBuilder()
            repeat(7) {
                seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
                sb.append('a' + ((seed ushr 8) % 26).toInt().let { if (it < 0) it + 26 else it })
            }
            return sb.toString()
        }
        (0 until rows).chunked(500).forEach { some ->
            db.vaultDao().insertAll(some.map { n ->
                val from = real[n % real.size]
                from.copy(
                    id = "scale-$n",
                    ocrText = from.ocrText + " " + (1..5).joinToString(" ") { nextWord() },
                    parentDocumentId = null,
                    contentHash = "",
                )
            })
        }
        return db
    }

    private companion object {
        const val SCALE_DB = "keyword-measure.db"
        val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")
    }
}
