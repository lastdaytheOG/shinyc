package com.amar.vault.dev

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.amar.vault.MigrationReport
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-contained navigation host for the (minimal) Developer Tools.
 *
 * Four utilities exist by design — Index Files, AI Models, Benchmarks (Sprint 3C), and Golden
 * Queries (the in-app way to write retrieval test cases) — to support the adb-push →
 * select-in-app testing workflow. Kept isolated in [com.amar.vault.dev] so the production
 * [com.amar.vault.Screen] graph has exactly ONE entry (DEV_TOOLS). Reachable only when
 * [DeveloperMode.isEnabled].
 */
enum class DevRoute { HUB, INDEX_FILES, AI_MODELS, BENCHMARKS, GOLDEN_QUERIES }

@Composable
fun DevToolsRoot(onExit: () -> Unit) {
    var route by remember { mutableStateOf(DevRoute.HUB) }
    val back = { route = DevRoute.HUB }

    when (route) {
        DevRoute.HUB -> DevToolsHub(onExit = onExit, onNavigate = { route = it })
        DevRoute.INDEX_FILES -> ManualIndexingScreen(onBack = back)
        DevRoute.AI_MODELS -> ModelDataManagerScreen(onBack = back)
        DevRoute.BENCHMARKS -> BenchmarkDashboardScreen(onBack = back)
        DevRoute.GOLDEN_QUERIES -> GoldenCaptureScreen(onBack = back)
    }
}

@Composable
private fun DevToolsHub(onExit: () -> Unit, onNavigate: (DevRoute) -> Unit) {
    DevScaffold(
        title = "Developer Tools",
        subtitle = "Index content · test local AI",
        onBack = onExit
    ) {
        DevNavCard("Index Files", "Pick files/folders or quick-scan, with pause/resume", "🗂️") { onNavigate(DevRoute.INDEX_FILES) }
        DevNavCard("AI Models", "Scan/import GGUF · load · test Summary/Chat/RAG", "🧠") { onNavigate(DevRoute.AI_MODELS) }
        DevNavCard("Benchmarks", "Run evaluation suites · regression vs baseline · reports", "📊") { onNavigate(DevRoute.BENCHMARKS) }
        DevNavCard("Golden Queries", "Type a real query · mark the right answers · scored by Benchmarks", "🎯") { onNavigate(DevRoute.GOLDEN_QUERIES) }
        Spacer(Modifier.height(8.dp))
        DatabaseUpgradeNote()
    }
}

/**
 * What the upgrade to database version 14 did to this device's vault, when it ran here. The
 * upgrade happens once, on data nobody else can see, so its own account is shown where it can
 * be read out. Nothing is drawn on a vault that was created at version 14.
 */
@Composable
private fun DatabaseUpgradeNote() {
    val context = LocalContext.current
    val upgrade = remember { MigrationReport.load(context) } ?: return
    val on = remember(upgrade.first) {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(upgrade.first))
    }
    DevSectionLabel("Database upgrade to version 14 · $on")
    // One fact to a line: the block scrolls sideways, not down.
    DevCard { DevMono(upgrade.second.replace("; ", "\n").replace(". Types renamed: ", "\nTypes renamed: ")) }
    Spacer(Modifier.height(8.dp))
}
