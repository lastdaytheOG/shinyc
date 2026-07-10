package com.amar.vault.benchmark

import android.content.Context
import com.amar.vault.VaultLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Report persistence — every run produces, under `filesDir/benchmark-results/`:
 *
 *   latest.json   full structured report (machine-readable, regression input)
 *   latest.csv    flat `section,metric,value,unit,higherIsBetter,note` rows
 *   latest.md     human-readable summary
 *   history/<runId>.json        every past run, for historical comparison
 *   baseline.json               the pinned "before" report (Module 11)
 *
 * All writes are atomic (tmp + rename). Reading tolerates missing/corrupt files by
 * returning null — the framework must never crash the app over a report file.
 */
class ReportStore(private val context: Context) {

    private val root: File get() = File(context.filesDir, "benchmark-results").apply { mkdirs() }
    private val historyDir: File get() = File(root, "history").apply { mkdirs() }

    val latestFile: File get() = File(root, "latest.json")
    val baselineFile: File get() = File(root, "baseline.json")

    fun write(report: BenchmarkRunReport) {
        atomicWrite(latestFile, report.toJson().toString(2))
        atomicWrite(File(root, "latest.csv"), toCsv(report))
        atomicWrite(File(root, "latest.md"), toMarkdown(report))
        atomicWrite(File(historyDir, "${report.runId}.json"), report.toJson().toString(2))
    }

    fun readLatest(): BenchmarkRunReport? = readReport(latestFile)

    fun readBaseline(): BenchmarkRunReport? = readReport(baselineFile)

    /** Pin the current latest report as the regression baseline ("before" snapshot). */
    fun promoteLatestToBaseline(): Boolean {
        val latest = readLatest() ?: return false
        atomicWrite(baselineFile, latest.toJson().toString(2))
        return true
    }

    fun history(): List<File> =
        historyDir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name } ?: emptyList()

    fun readReport(file: File): BenchmarkRunReport? {
        if (!file.exists()) return null
        return runCatching { BenchmarkRunReport.fromJson(org.json.JSONObject(file.readText())) }
            .onFailure { VaultLog.w("ReportStore", "Unreadable report ${file.name}: ${it.message}") }
            .getOrNull()
    }

    fun newRunId(suite: BenchmarkSuite): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$stamp-${suite.name.lowercase(Locale.US)}"
    }

    private fun atomicWrite(target: File, content: String) {
        try {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            VaultLog.w("ReportStore", "Failed writing ${target.name}: ${e.message}")
        }
    }

    // ── Formats ──────────────────────────────────────────────────────────────

    private fun toCsv(report: BenchmarkRunReport): String = buildString {
        appendLine("section,metric,value,unit,higherIsBetter,note")
        for (section in report.sections) {
            for (m in section.metrics) {
                appendLine(listOf(
                    section.id, m.name, m.value?.toString() ?: "", m.unit,
                    m.higherIsBetter.toString(), m.note
                ).joinToString(",") { csvEscape(it) })
            }
        }
    }

    private fun csvEscape(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"${v.replace("\"", "\"\"")}\"" else v

    private fun toMarkdown(report: BenchmarkRunReport): String = buildString {
        appendLine("# Amar Vault benchmark — ${report.runId}")
        appendLine()
        appendLine("- Suite: **${report.suite}**")
        appendLine("- Device: ${report.deviceModel} (SDK ${report.sdkInt})")
        appendLine("- Corpus: ${report.corpusDocumentCount} documents")
        appendLine("- Duration: ${report.durationMs} ms")
        for (note in report.notes) appendLine("- Note: $note")
        for (section in report.sections) {
            appendLine()
            appendLine("## ${section.title}")
            appendLine()
            appendLine("| Metric | Value | Unit | Note |")
            appendLine("|---|---:|---|---|")
            for (m in section.metrics) {
                val v = m.value?.let { "%.4f".format(Locale.US, it).trimEnd('0').trimEnd('.') } ?: "—"
                appendLine("| ${m.name} | $v | ${m.unit} | ${m.note} |")
            }
        }
        appendLine()
    }
}
