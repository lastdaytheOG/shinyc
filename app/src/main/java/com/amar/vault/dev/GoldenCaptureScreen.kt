package com.amar.vault.dev

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.VaultItem
import com.amar.vault.benchmark.BenchmarkCase
import com.amar.vault.benchmark.BenchmarkContentType
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.benchmark.GoldenDatasetStore
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.CreamDark
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.WarmBrown
import com.amar.vault.ui.theme.WarmBrownDark
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One document a person can mark as a right answer. [docId] is the id the evaluator scores on. */
internal data class GoldenCandidate(
    val docId: String,
    val label: String,
    val type: String,
    val snippet: String,
)

private val WHITESPACE = Regex("\\s+")

/** The name shown for an item: its title, else its file name, else the tail of its uri. */
internal fun goldenLabel(item: VaultItem): String =
    item.title?.takeIf { it.isNotBlank() }
        ?: item.sourceFile.ifBlank { item.uri.substringAfterLast('/') }

/** Chunk rows collapse onto their parent document, exactly as RetrievalEvaluator scores them. */
internal fun VaultItem.toGoldenCandidate(): GoldenCandidate = GoldenCandidate(
    docId = parentDocumentId ?: id,
    label = goldenLabel(this),
    type = itemType,
    snippet = ocrText.replace(WHITESPACE, " ").trim().take(140),
)

internal fun goldenContentType(itemType: String): BenchmarkContentType = when (itemType.lowercase()) {
    "pdf", "word", "excel", "epub" -> BenchmarkContentType.PDF
    "screenshot" -> BenchmarkContentType.SCREENSHOT
    "link" -> BenchmarkContentType.SAVED_LINK
    else -> BenchmarkContentType.IMAGE
}

private const val MAX_ROWS = 20

/**
 * Dev Tools → Golden Queries. Writes retrieval test cases the way a person can actually do it:
 * type a real query, tap the documents that are the right answer, save. Cases land in the
 * device-side `captured` dataset and are scored by Benchmarks → Retrieval on the next run.
 *
 * The right answer can be picked from the search results OR found by file name — the second
 * path matters, because a case where search misses the right document is exactly the failure
 * the benchmark exists to count, and it could never be captured from the result list alone.
 *
 * Dev-only (reachable exclusively through [DevToolsRoot]). Observes production services
 * through the benchmark entry point; writes nothing except the captured dataset file.
 */
@Composable
fun GoldenCaptureScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val services = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext, BenchmarkRunner.BenchmarkEntryPoint::class.java
        )
    }
    val store = remember { GoldenDatasetStore(context.applicationContext) }

    var query by remember { mutableStateOf("") }
    var searchedQuery by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<GoldenCandidate>>(emptyList()) }
    var nameFilter by remember { mutableStateOf("") }
    var nameMatches by remember { mutableStateOf<List<GoldenCandidate>>(emptyList()) }
    var picked by remember { mutableStateOf<List<GoldenCandidate>>(emptyList()) }
    var saved by remember { mutableStateOf<List<BenchmarkCase>>(emptyList()) }
    var status by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { store.capturedCases() } }

    fun toggle(candidate: GoldenCandidate) {
        picked = if (picked.any { it.docId == candidate.docId }) picked.filterNot { it.docId == candidate.docId }
        else picked + candidate
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        busy = true
        status = ""
        scope.launch {
            try {
                // Same call the evaluator scores: the plain query through the one retrieval pipeline.
                val items = services.retrievalService().retrieve(RetrievalRequest(q)).items
                results = items.map { it.toGoldenCandidate() }.distinctBy { it.docId }.take(MAX_ROWS)
                searchedQuery = q
                picked = emptyList()
                if (results.isEmpty()) status = "Search found nothing. Find the right document by name below."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Search failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun runNameFind() {
        val needle = nameFilter.trim()
        if (needle.length < 2 || busy) return
        busy = true
        status = ""
        scope.launch {
            try {
                nameMatches = withContext(Dispatchers.Default) {
                    services.searchRepository().allItemsSnapshot()
                        .filter { goldenLabel(it).contains(needle, ignoreCase = true) }
                        .distinctBy { it.parentDocumentId ?: it.id }
                        .take(MAX_ROWS)
                        .map { it.toGoldenCandidate() }
                }
                if (nameMatches.isEmpty()) status = "No indexed document has \"$needle\" in its name."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Lookup failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        val q = searchedQuery
        val chosen = picked
        if (q.isBlank() || chosen.isEmpty() || busy) return
        busy = true
        scope.launch {
            try {
                saved = withContext(Dispatchers.IO) {
                    store.appendCapturedCase(
                        query = q,
                        expectedDocumentIds = chosen.map { it.docId },
                        contentType = goldenContentType(chosen.first().type),
                        notes = "captured in Dev Tools → Golden Queries",
                    )
                    store.capturedCases()
                }
                status = "Saved. ${saved.size} case(s) captured."
                query = ""; searchedQuery = ""; nameFilter = ""
                results = emptyList(); nameMatches = emptyList(); picked = emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Not saved: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun remove(case: BenchmarkCase) {
        scope.launch {
            try {
                saved = withContext(Dispatchers.IO) {
                    store.removeCapturedCase(case.id)
                    store.capturedCases()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Not removed: ${e.message}"
            }
        }
    }

    DevScaffold(
        title = "Golden Queries",
        subtitle = "Type a real query · mark the right answers · save",
        onBack = onBack
    ) {
        DevCard {
            Text(
                "Mark what you KNOW is the right document, not whatever ranks first. " +
                    "Around 50 saved cases is enough for Benchmarks → Retrieval to give real numbers.",
                fontSize = 13.sp, color = WarmBrownDark
            )
        }

        DevSectionLabel("1 · Query")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("What you would really type") },
        )
        Spacer(Modifier.height(8.dp))
        DevButton(if (busy) "Working…" else "Search", onClick = { runSearch() }, enabled = !busy && query.isNotBlank())

        if (results.isNotEmpty()) {
            DevSectionLabel("2 · Tap every correct result")
            results.forEachIndexed { index, candidate ->
                CandidateRow(index + 1, candidate, picked.any { it.docId == candidate.docId }) { toggle(candidate) }
            }
        }

        if (searchedQuery.isNotBlank()) {
            DevSectionLabel("Right answer not in the list? Find it by file name")
            OutlinedTextField(
                value = nameFilter,
                onValueChange = { nameFilter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Part of the file name or title") },
            )
            Spacer(Modifier.height(8.dp))
            DevOutlineButton("Find", onClick = { runNameFind() }, enabled = !busy && nameFilter.trim().length >= 2)
            Spacer(Modifier.height(8.dp))
            nameMatches.forEach { candidate ->
                CandidateRow(null, candidate, picked.any { it.docId == candidate.docId }) { toggle(candidate) }
            }

            DevSectionLabel("3 · Save")
            DevKeyValue("Query", searchedQuery)
            DevKeyValue("Marked correct", picked.joinToString { it.label }.ifBlank { "none yet" })
            Spacer(Modifier.height(8.dp))
            DevButton("Save case", onClick = { save() }, enabled = !busy && picked.isNotEmpty())
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 13.sp, color = WarmBrownDark)
        }

        DevSectionLabel("Saved cases (${saved.size})")
        if (saved.isEmpty()) {
            Text("None yet.", fontSize = 13.sp, color = WarmBrownDark)
        }
        saved.asReversed().forEach { case ->
            DevCard {
                Text(
                    case.queries.firstOrNull().orEmpty(),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft
                )
                Text("${case.expectedResults.size} correct document(s)", fontSize = 12.sp, color = WarmBrownDark)
                Spacer(Modifier.height(8.dp))
                DevOutlineButton("Remove", onClick = { remove(case) })
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CandidateRow(rank: Int?, candidate: GoldenCandidate, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) CreamDark else CreamLight),
        border = BorderStroke(1.dp, if (selected) WarmBrown else CreamDark),
        onClick = onClick
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(
                text = if (selected) "✓" else rank?.toString() ?: "·",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = WarmBrownDark,
                modifier = Modifier.width(28.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    candidate.label,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CharcoalSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(candidate.type, fontSize = 11.sp, color = WarmBrown)
                if (candidate.snippet.isNotBlank()) {
                    Text(
                        candidate.snippet,
                        fontSize = 12.sp, color = WarmBrownDark,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
