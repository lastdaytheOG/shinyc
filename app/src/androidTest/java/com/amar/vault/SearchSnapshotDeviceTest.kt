package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.BenchmarkRunner
import com.amar.vault.retrieval.RetrievalRequest
import com.amar.vault.retrieval.SearchableName
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Writes down what the search screen lists for several hundred queries against whatever is
 * indexed on the device, so that two builds can be compared: a change that is meant to leave
 * search alone (a database migration, a faster engine) must produce the same file.
 *
 * It asserts nothing about the lists themselves. It is run on request only:
 *
 *     adb shell am instrument -w -e class com.amar.vault.SearchSnapshotDeviceTest \
 *         -e snapshot record com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * `record` works the queries out from the stored text and keeps them in the app's files
 * (`search-snapshot/queries.txt`); `replay` runs those same queries again. Each writes
 * `search-snapshot/<mode>.jsonl`, one line per query, to be pulled with `run-as` and compared.
 */
@RunWith(AndroidJUnit4::class)
class SearchSnapshotDeviceTest {

    private val app by lazy { InstrumentationRegistry.getInstrumentation().targetContext.applicationContext }
    private val services by lazy {
        EntryPointAccessors.fromApplication(app, BenchmarkRunner.BenchmarkEntryPoint::class.java)
    }
    private val folder by lazy { File(app.filesDir, "search-snapshot").apply { mkdirs() } }

    @Test
    fun snapshot() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("snapshot")
        assumeTrue("run with -e snapshot record|replay", mode == "record" || mode == "replay")

        // The app fills the keyword engine from the database when it starts; a search waits
        // for that by itself.
        val rows = services.database().vaultDao().getAll()
        assertTrue("nothing is indexed on this device", rows.isNotEmpty())

        val queriesFile = File(folder, "queries.txt")
        val queries = if (mode == "record") queriesFrom(rows).also { queriesFile.writeText(it.joinToString("\n")) }
        else queriesFile.readLines().filter { it.isNotBlank() }

        val saved = emptyMap<String, StashItemWithVaultItem>()
        File(folder, "$mode.jsonl").bufferedWriter().use { out ->
            queries.forEachIndexed { index, query ->
                // Every query unfiltered; every fifth one through the two chips as well.
                val chips = if (index % 5 == 0) listOf(SearchFilter.ALL, SearchFilter.IMAGES, SearchFilter.DOCUMENTS)
                else listOf(SearchFilter.ALL)
                for (chip in chips) {
                    val only: ((VaultItem) -> Boolean)? =
                        if (chip == SearchFilter.ALL) null else { item -> SearchFilter.accepts(chip, item, saved = null) }
                    val result = services.retrievalService().retrieve(RetrievalRequest.forResultList(query, only))
                    val cards = oneCardPerDocument(result.items.map { it.asRow() }, result.items, saved, queryWordsOf(query))
                    out.write(JSONObject().apply {
                        put("q", query)
                        put("chip", chip)
                        put("similarOnly", result.similarSpellingsOnly)
                        put("items", JSONArray(result.items.map { "${it.id}@${it.pageNum}" }))
                        put("cards", JSONArray(cards.map { card ->
                            JSONObject().apply {
                                put("id", card.row.vaultItemId)
                                put("page", card.page ?: JSONObject.NULL)
                                put("excerpt", card.excerpt ?: JSONObject.NULL)
                                put("similar", card.similarWord ?: JSONObject.NULL)
                                put("filedUnder", card.filedUnder ?: JSONObject.NULL)
                            }
                        }))
                    }.toString())
                    out.newLine()
                }
            }
        }
    }

    /** The row the search screen draws for an item that was never saved. */
    private fun VaultItem.asRow() = StashItemWithVaultItem(
        stashId = "search_$id", sessionId = null, vaultItemId = id, vaultType = "SAVED", category = "",
        savedAt = timestamp, sourceApp = sourceApp ?: "", isFavorite = false, createdAt = timestamp,
        userNote = null, thumbnailPath = null, uri = uri, ocrText = ocrText, itemType = itemType,
        sourceFile = sourceFile, timestamp = timestamp, title = title, mimeType = mimeType,
        tags = tags, parentDocumentId = parentDocumentId,
    )

    private fun queriesFrom(rows: List<VaultItem>): List<String> {
        val pages = rows.map { it.ocrText }
        val words = pages.asSequence()
            .flatMap { WORD.findAll(it.lowercase()).map { m -> m.value } }
            .filter { it.length in 3..24 }.toSortedSet().toList()
        fun <T> List<T>.every(step: Int, from: Int = 0) = filterIndexed { i, _ -> i >= from && (i - from) % step == 0 }
        val sampled = words.every((words.size / 260).coerceAtLeast(1))
        val long = words.filter { it.length >= 6 && it.all { c -> c in 'a'..'z' } }
        val longSampled = long.every((long.size / 60).coerceAtLeast(1))

        val out = LinkedHashSet<String>()
        // What was reported from a phone on 2026-10-07, and words every vault has.
        out += listOf(
            "claude", "Claude", "CLAUDE", "brenda", "Brenda", "last", "last working days", "Last Working Days",
            "LAST WORKING DAYS", "working days", "receipt", "invoice", "payment", "pdf", "PDF", "document",
            "screenshot", "image", "photo", "exam", "example", "tax", "pan", "company", "report", "total",
            "amount", "marks", "semester", "calendar", "handbook", "act 2023", "2023", "2026", "march 2026",
            "claude?", "(queue)", "invoice,", "queen", "settings", "now playing", "ella ciao",
        )
        out += sampled
        // The tags the indexers add, and what the files are called.
        out += rows.flatMap { it.tags.lowercase().split(' ') }.filter { it.length >= 2 }.toSortedSet()
        out += rows.map { SearchableName.of(it) }.filter { it.isNotBlank() }.toSortedSet()
            .flatMap { WORD.findAll(it.lowercase()).map { m -> m.value }.filter { w -> w.length >= 3 }.toList() }.toSortedSet()
        // Two and three words as they stand next to each other on a page.
        pages.every((pages.size / 70).coerceAtLeast(1)).forEachIndexed { i, page ->
            val onPage = WORD.findAll(page.lowercase()).map { it.value }.filter { it.length >= 3 }.toList()
            if (onPage.size < 12) return@forEachIndexed
            val at = onPage.size / 2
            out += onPage.subList(at, at + if (i % 3 == 0) 3 else 2).joinToString(" ")
        }
        // Misspellings: a letter left out, two letters changed places, a letter doubled.
        longSampled.forEachIndexed { i, w ->
            out += when (i % 3) {
                0 -> w.removeRange(2, 3)
                1 -> w.substring(0, 2) + w[3] + w[2] + w.substring(4)
                else -> w.substring(0, 3) + w[2] + w.substring(3)
            }
        }
        // Capitals, and punctuation typed onto a word.
        longSampled.every(4).forEach { out += it.uppercase(); out += it.replaceFirstChar { c -> c.uppercase() } }
        longSampled.every(6, from = 1).forEach { out += "$it?"; out += "($it)" }
        return out.filter { it.isNotBlank() }
    }

    private companion object {
        val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")
    }
}
