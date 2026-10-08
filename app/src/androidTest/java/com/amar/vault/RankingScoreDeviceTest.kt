package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.benchmark.GoldenDatasetStore
import com.amar.vault.benchmark.RetrievalAblationBenchmark
import com.amar.vault.benchmark.RetrievalEvaluator
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Scores ranking on the device: every golden query, through the real search, once for each
 * ranking switch. It asserts nothing about the numbers; it writes them down.
 *
 *     adb shell am instrument -w -e class com.amar.vault.RankingScoreDeviceTest \
 *         -e ranking score com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * The golden queries are the ones on the device (the .json files in `files/benchmark/GoldenDataset`: the
 * starter set pushed from `tools/vault-check/golden-starter.json`, and whatever was entered in
 * Dev Tools → Golden Queries). It writes `files/ranking-score/summary.tsv` — one line per
 * switch — and `queries.tsv` — for each switch and query, where the first right answer stood
 * and what was listed first.
 */
@RunWith(AndroidJUnit4::class)
class RankingScoreDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val services = EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java)
    private val folder = File(app.filesDir, "ranking-score").apply { mkdirs() }

    @Test
    fun score(): Unit = runBlocking {
        assumeTrue("run with -e ranking score", InstrumentationRegistry.getArguments().getString("ranking") == "score")
        val cases = GoldenDatasetStore(app).allCases().filter { it.supportsRetrieval }
        val evaluator = RetrievalEvaluator(services.retrievalService(), services.searchRepository())
        evaluator.scoreAll(cases) // untimed: storage and the engine are warm for every variant after it

        val summary = StringBuilder("variant\tqueries\tskipped\tmrr\tfirst\tinTop3\trightOnTop\tnotFound\tlatencyMs\twhat\n")
        val queries = StringBuilder("variant\tkind\tquery\trank\ttop\trightOnTop\n")
        val kinds = StringBuilder("variant\tkind\tqueries\tmrr\tfirst\n")
        // Without the embedding model the switches that only steer it change nothing: they are
        // left to a build that has the model.
        val hasModel = services.semanticRetriever().isReady
        val variants = RetrievalAblationBenchmark.VARIANTS.filter {
            hasModel || (it.name != "keywordOnly" && it.name != "paddedQuery" && !it.name.startsWith("semanticHits"))
        }
        for (variant in variants) {
            val scored = evaluator.scoreAll(cases, variant.tuning)
            val scores = scored.scores
            assertTrue("no golden query could be scored: ${scored.rows.take(3)}", scores.isNotEmpty())
            fun share(count: Int) = "%.3f".format(java.util.Locale.US, count.toDouble() / scores.size)
            summary.append(variant.name).append('\t').append(scores.size).append('\t').append(scored.skipped).append('\t')
                .append("%.4f".format(java.util.Locale.US, scores.map { it.mrr }.average())).append('\t')
                .append(share(scores.count { it.rank == 1 })).append('\t')
                .append(share(scores.count { it.rank in 1..3 })).append('\t')
                .append("%.4f".format(java.util.Locale.US, scores.map { it.rightOnTop }.average())).append('\t')
                .append(scores.count { it.rank == 0 }).append('\t')
                .append("%.1f".format(java.util.Locale.US, scored.latenciesMs.average())).append('\t')
                .append(variant.what).append('\n')
            for (score in scores) {
                queries.append(variant.name).append('\t').append(score.kind).append('\t').append(score.query).append('\t')
                    .append(score.rank).append('\t').append(score.top).append('\t')
                    .append("%.2f".format(java.util.Locale.US, score.rightOnTop)).append('\n')
            }
            for ((kind, ofKind) in scores.groupBy { it.kind }) {
                kinds.append(variant.name).append('\t').append(kind).append('\t').append(ofKind.size).append('\t')
                    .append("%.4f".format(java.util.Locale.US, ofKind.map { it.mrr }.average())).append('\t')
                    .append("%.3f".format(java.util.Locale.US, ofKind.count { it.rank == 1 }.toDouble() / ofKind.size)).append('\n')
            }
        }
        File(folder, "summary.tsv").writeText(summary.toString())
        File(folder, "queries.tsv").writeText(queries.toString())
        File(folder, "kinds.tsv").writeText(kinds.toString())
    }
}
