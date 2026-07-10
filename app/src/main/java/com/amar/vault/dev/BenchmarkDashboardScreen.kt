package com.amar.vault.dev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amar.vault.benchmark.BenchmarkRunReport
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.benchmark.BenchmarkSection
import com.amar.vault.benchmark.BenchmarkSuite
import com.amar.vault.benchmark.OcrContributionAggregator
import com.amar.vault.indexing.OcrInstrumentation
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Module 12 — Developer benchmark dashboard.
 *
 * Dev-only (reachable exclusively through [DevToolsRoot], which is gated behind
 * [DeveloperMode]). Runs suites via the real [BenchmarkRunner], shows the latest
 * report's sections, regression verdicts, and history, and pins baselines.
 * Read/execute only — no production UI or behaviour is touched.
 */
@Composable
fun BenchmarkDashboardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { BenchmarkRunner.create(context) }

    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<BenchmarkRunReport?>(null) }
    var baselineId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }

    // ── Production Evaluation Mode (Sprint E1): capture OCR contribution from REAL indexing ──
    var capture by remember { mutableStateOf(OcrInstrumentation.enabled) }
    var prodSamples by remember { mutableStateOf(0) }
    var prodSection by remember { mutableStateOf<BenchmarkSection?>(null) }

    fun refreshProduction() {
        val reports = OcrInstrumentation.snapshot()
        prodSamples = reports.size
        prodSection = OcrContributionAggregator.section(
            reports, 0,
            id = "ocr.production",
            title = "Production OCR contribution (live samples)",
            emptyNote = "no production OCR samples captured yet — turn capture ON, then index images/screenshots/PDFs",
        )
    }

    fun refresh() {
        report = runner.reports.readLatest()
        baselineId = runner.reports.readBaseline()?.runId
    }
    LaunchedEffect(Unit) { refresh(); refreshProduction() }

    fun launchSuite(suite: BenchmarkSuite) {
        if (running) return
        running = true
        status = ""
        scope.launch {
            try {
                report = runner.run(suite, scope) { progress = it }
                baselineId = runner.reports.readBaseline()?.runId
                status = "Run complete → benchmark-results/latest.{json,csv,md}"
            } catch (e: Exception) {
                status = "Run failed: ${e.message}"
            } finally {
                running = false
                progress = ""
            }
        }
    }

    DevScaffold(
        title = "Benchmarks",
        subtitle = "Measure indexing · retrieval · OCR · embeddings · RAM · storage",
        onBack = onBack,
    ) {
        DevSectionLabel("Run suite")
        DevCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevButton("Full", { launchSuite(BenchmarkSuite.FULL) }, enabled = !running, modifier = Modifier.weight(1f))
                DevOutlineButton("Retrieval", { launchSuite(BenchmarkSuite.RETRIEVAL) }, enabled = !running, modifier = Modifier.weight(1f))
                DevOutlineButton("OCR", { launchSuite(BenchmarkSuite.OCR) }, enabled = !running, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevOutlineButton("Embedding", { launchSuite(BenchmarkSuite.EMBEDDING) }, enabled = !running, modifier = Modifier.weight(1f))
                DevOutlineButton("Performance", { launchSuite(BenchmarkSuite.PERFORMANCE) }, enabled = !running, modifier = Modifier.weight(1f))
            }
            if (running) {
                Spacer(Modifier.height(10.dp))
                DevKeyValue("Running", progress.ifBlank { "starting…" })
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                DevMono(status)
            }
        }

        DevSectionLabel("Production Evaluation Mode (OCR contribution from real indexing)")
        DevCard {
            DevButton(
                if (capture) "● Capturing — tap to stop" else "○ Capture OFF — tap to start",
                {
                    capture = !capture
                    OcrInstrumentation.enabled = capture
                    // Sprint E3: the same toggle drives the doc/page indexing profiler —
                    // one capture session feeds both evaluation reports.
                    com.amar.vault.indexing.IndexingProfiler.enabled = capture
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            DevKeyValue("Capture", if (capture) "ON — every indexed image / PDF fallback page is sampled (+ doc/page profiler)" else "OFF (indexing behaves normally)")
            DevKeyValue("Samples collected", prodSamples.toString())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevOutlineButton("Refresh stats", { refreshProduction() }, modifier = Modifier.weight(1f))
                DevOutlineButton("Clear", {
                    OcrInstrumentation.clear()
                    com.amar.vault.indexing.IndexingProfiler.clear()
                    refreshProduction()
                }, modifier = Modifier.weight(1f))
            }
            val ps = prodSection
            if (ps != null && prodSamples > 0) {
                Spacer(Modifier.height(10.dp))
                for (m in ps.metrics) {
                    DevKeyValue(
                        m.name,
                        m.value?.let { formatValue(it, m.unit) } ?: "— (${m.note.ifBlank { "unmeasured" }})"
                    )
                }
            } else {
                Spacer(Modifier.height(6.dp))
                DevMono("No production samples yet. Turn capture ON, index some images/screenshots/PDFs, then Refresh stats.")
            }
        }

        DevSectionLabel("Baseline (regression 'before' snapshot)")
        DevCard {
            DevKeyValue("Pinned baseline", baselineId ?: "none")
            Spacer(Modifier.height(8.dp))
            Row {
                DevOutlineButton("Pin latest as baseline", {
                    if (runner.reports.promoteLatestToBaseline()) {
                        baselineId = runner.reports.readBaseline()?.runId
                        status = "Baseline pinned"
                    } else status = "No latest report to pin — run a suite first"
                }, enabled = !running)
                Spacer(Modifier.width(8.dp))
                DevOutlineButton("Reload", { refresh() }, enabled = !running)
            }
        }

        val r = report
        if (r == null) {
            DevSectionLabel("Latest report")
            DevCard { DevKeyValue("Status", "No benchmark has been run yet") }
        } else {
            DevSectionLabel("Latest report — ${r.runId}")
            DevCard {
                DevKeyValue("Suite", r.suite.name)
                DevKeyValue("Corpus", "${r.corpusDocumentCount} documents")
                DevKeyValue("Duration", "${r.durationMs} ms")
                DevKeyValue("Device", "${r.deviceModel} (SDK ${r.sdkInt})")
                r.notes.forEach { DevKeyValue("Note", it) }
            }
            for (section in r.sections) {
                DevSectionLabel(section.title)
                DevCard {
                    for (m in section.metrics) {
                        DevKeyValue(
                            m.name,
                            m.value?.let { formatValue(it, m.unit) } ?: "— (${m.note.ifBlank { "unmeasured" }})"
                        )
                    }
                    if (section.id == "regression" && section.rows.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        DevMono(section.rows.joinToString("\n") { row ->
                            "${row["verdict"]}  ${row["section"]}/${row["metric"]}  ${row["baseline"]} → ${row["current"]}  (${row["deltaPercent"]}%)"
                        })
                    }
                }
            }
        }

        DevSectionLabel("History")
        DevCard {
            val history = runner.reports.history().take(10)
            if (history.isEmpty()) DevKeyValue("Runs", "none")
            else history.forEach { DevKeyValue(it.name.removeSuffix(".json"), "%.1f KB".format(Locale.US, it.length() / 1024.0)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun formatValue(v: Double, unit: String): String {
    val num = when {
        unit == "bytes" && v >= 1_048_576 -> "%.2f MB".format(Locale.US, v / 1_048_576)
        unit == "bytes" && v >= 1024 -> "%.1f KB".format(Locale.US, v / 1024)
        v == v.toLong().toDouble() -> v.toLong().toString()
        else -> "%.4f".format(Locale.US, v).trimEnd('0').trimEnd('.')
    }
    return if (unit.isBlank() || unit == "bytes") num else "$num $unit"
}
