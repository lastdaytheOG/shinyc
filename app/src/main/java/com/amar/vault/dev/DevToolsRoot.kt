package com.amar.vault.dev

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Self-contained navigation host for the (minimal) Developer Tools.
 *
 * Three utilities exist by design — Index Files, AI Models, and Benchmarks (Sprint 3C) — to
 * support the adb-push → select-in-app testing workflow. Kept isolated in [com.amar.vault.dev] so the production
 * [com.amar.vault.Screen] graph has exactly ONE entry (DEV_TOOLS). Reachable only when
 * [DeveloperMode.isEnabled].
 */
enum class DevRoute { HUB, INDEX_FILES, AI_MODELS, BENCHMARKS }

@Composable
fun DevToolsRoot(onExit: () -> Unit) {
    var route by remember { mutableStateOf(DevRoute.HUB) }
    val back = { route = DevRoute.HUB }

    when (route) {
        DevRoute.HUB -> DevToolsHub(onExit = onExit, onNavigate = { route = it })
        DevRoute.INDEX_FILES -> ManualIndexingScreen(onBack = back)
        DevRoute.AI_MODELS -> ModelDataManagerScreen(onBack = back)
        DevRoute.BENCHMARKS -> BenchmarkDashboardScreen(onBack = back)
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
        Spacer(Modifier.height(8.dp))
    }
}
