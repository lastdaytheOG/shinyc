package com.amar.vault.dev

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.LocalModel
import com.amar.vault.ModelManager
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import kotlinx.coroutines.launch

@Composable
fun ModelDataManagerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val models by ModelManager.getInstance(context).getAllModelsFlow().collectAsState(initial = emptyList())
    val loadDurations by DevModelManager.loadDurations.collectAsState()
    var scanned by remember { mutableStateOf<List<DevModelManager.ScannedModel>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }

    val scanFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            scanned = DevModelManager.scanFolder(context, treeUri)
            note = if (scanned.isEmpty()) "No .gguf files found in that folder." else null
        }
    }

    DevScaffold(title = "AI Models", subtitle = "Reuses ModelManager · LanguageModel · RagService", onBack = onBack) {

        DevSectionLabel("Find models")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DevButton("Scan folder…", { scanFolder.launch(null) }, modifier = Modifier.weight(1f))
            DevOutlineButton("Refresh", {
                scope.launch { note = "Registered ${DevModelManager.refreshManaged(context)} model(s) from app directory." }
            }, modifier = Modifier.weight(1f))
        }
        if (note != null) {
            Spacer(Modifier.height(8.dp))
            Text(note!!, fontSize = 12.sp, color = WarmBrown)
        }

        if (scanned.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Found ${scanned.size} model file(s):", fontSize = 12.sp, color = WarmBrownDark)
            scanned.forEach { m ->
                DevCard {
                    Text(m.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft)
                    DevKeyValue("Size", DevModelManager.formatSize(m.sizeBytes))
                    DevKeyValue("Quant", DevModelManager.quantOf(m.name))
                    Spacer(Modifier.height(6.dp))
                    DevButton("Import into Amar Vault", {
                        scope.launch {
                            val r = DevModelManager.importModel(context, m)
                            note = r.fold({ "Imported $it" }, { "Import failed: ${it.message}" })
                            scanned = scanned - m
                        }
                    }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        DevSectionLabel("Managed models (${models.size})")
        if (models.isEmpty()) {
            Text("No models registered. Scan a folder or adb-push a .gguf and Refresh.", fontSize = 13.sp, color = WarmBrown)
        }
        models.forEach { model ->
            ManagedModelCard(
                model = model,
                loadMs = loadDurations[model.modelId],
                path = DevModelManager.modelPath(context, model.fileName),
                fileExists = DevModelManager.fileExists(context, model.fileName),
                onLoad = { scope.launch { DevModelManager.load(context, model.modelId) } },
                onUnload = { scope.launch { DevModelManager.unload(context, model.modelId) } },
                onReload = { scope.launch { DevModelManager.reload(context, model.modelId) } },
                onRemove = { scope.launch { DevModelManager.remove(context, model) } },
            )
            Spacer(Modifier.height(8.dp))
        }

        ValidationSection()

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ManagedModelCard(
    model: LocalModel,
    loadMs: Long?,
    path: String,
    fileExists: Boolean,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onReload: () -> Unit,
    onRemove: () -> Unit,
) {
    DevCard {
        Row {
            Text(model.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft, modifier = Modifier.weight(1f))
            if (model.isEnabled) Text("● ACTIVE", fontSize = 11.sp, color = com.amar.vault.ui.theme.LocationColor, fontWeight = FontWeight.Bold)
        }
        DevKeyValue("File", model.fileName)
        DevKeyValue("Size", DevModelManager.formatSize(model.sizeBytes))
        DevKeyValue("Quant", DevModelManager.quantOf(model.fileName))
        DevKeyValue("State", if (!fileExists) "MISSING FILE" else model.status)
        DevKeyValue("Last load", if (loadMs != null) "$loadMs ms" else "—")
        DevKeyValue("Path", path)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DevButton("Load", onLoad, enabled = model.status == "READY" && !model.isEnabled, modifier = Modifier.weight(1f))
            DevButton("Reload", onReload, enabled = model.status == "READY", modifier = Modifier.weight(1f))
            DevOutlineButton("Unload", onUnload, enabled = model.isEnabled, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        DevOutlineButton("Remove", onRemove, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ValidationSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentModel by remember { mutableStateOf("—") }
    var log by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { currentModel = DevValidations.currentModel(context) }

    fun run(action: suspend () -> String) {
        if (running) return
        running = true
        scope.launch {
            val start = System.currentTimeMillis()
            val result = runCatching { action() }.getOrElse { "ERROR: ${it.message}" }
            val ms = System.currentTimeMillis() - start
            log = listOf("[$ms ms] $result") + log
            currentModel = DevValidations.currentModel(context)
            running = false
        }
    }

    DevSectionLabel("Active model & load status")
    DevCard {
        Text(currentModel, fontSize = 12.sp, color = CharcoalSoft)
    }

    DevSectionLabel("One-click tests")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DevButton("Test Document Summary", { run { DevValidations.testSummary(context) } }, enabled = !running, modifier = Modifier.fillMaxWidth())
        DevButton("Test Document Chat", { run { DevValidations.testChat(context) } }, enabled = !running, modifier = Modifier.fillMaxWidth())
        DevButton("Test RAG", { run { DevValidations.testRag(context) } }, enabled = !running, modifier = Modifier.fillMaxWidth())
    }

    if (log.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        DevSectionLabel("Results")
        log.take(8).forEach {
            DevMono(it)
            Spacer(Modifier.height(6.dp))
        }
    }
}
