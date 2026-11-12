package com.amar.vault.benchmark

import android.graphics.BitmapFactory
import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultDocument
import com.amar.vault.VaultItem
import com.amar.vault.indexing.AutoTags
import com.amar.vault.indexing.DocumentRows
import com.amar.vault.indexing.IndexPersister
import com.amar.vault.indexing.MetadataStage
import com.amar.vault.indexing.PagedChunk
import com.amar.vault.indexing.SentenceAwareChunker
import com.amar.vault.photos.PhotoSearch
import com.amar.vault.retrieval.Bm25Index
import com.amar.vault.retrieval.KeywordIndexFill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BenchVault(
    private val db: VaultDatabase,
    private val bm25: Bm25Index,
    private val photos: PhotoSearch?,
    private val dates: BenchDates,
) {

    class Loaded(val documents: Int, val rows: Int, val pictures: Int, val photoVectors: Int, val ms: Long)

    suspend fun load(corpus: File, photoDir: File?, onItem: (Int) -> Unit = {}): Loaded {
        val t0 = System.currentTimeMillis()
        val persister = IndexPersister(db)
        val meta = MetadataStage()
        val chunker = SentenceAwareChunker()
        var documents = 0
        var rows = 0
        var pictures = 0
        var vectors = 0
        corpus.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEachIndexed { n, line ->
                val j = JSONObject(line)
                val id = j.getString("id")
                val date = j.getJSONObject("date")
                val ts = dates.at(date.optIntOrNull("monthsAgo"), date.optIntOrNull("day"), date.optIntOrNull("daysAgo"))
                val pages = j.getJSONArray("stored").let { a -> List(a.length()) { dates.resolve(a.getString(it)) } }
                when (val type = j.getString("type")) {
                    "PDF", "WORD" -> {
                        val itemType = if (type == "PDF") ItemType.PDF else ItemType.WORD
                        val doc = VaultDocument(
                            id = id, uri = "bench://$id", name = j.getString("name"), itemType = itemType,
                            contentHash = "bench-$id", pageCount = if (itemType == ItemType.PDF) pages.size else null,
                            chunkCount = 0, addedAt = ts,
                        )
                        var index = 0
                        val pieces = pages.flatMapIndexed { p, text ->
                            chunker.chunk(text).filter { it.isNotBlank() }.map { piece ->
                                PagedChunk(piece, if (itemType == ItemType.PDF) p + 1 else null, index++)
                            }
                        }
                        val made = DocumentRows.of(doc, pieces, pieces.size).map { it.copy(timestamp = ts) }
                        persister.persistDocument(doc, made)
                        documents++
                        rows += made.size
                    }
                    else -> {
                        val itemType = if (type == "SCREENSHOT") ItemType.SCREENSHOT else ItemType.PHOTO
                        val text = pages.joinToString("\n")
                        val hash = (id.hashCode().toLong() shl 20) or 0x5A5AL
                        val item = VaultItem(
                            id = id, uri = "bench://$id", ocrText = text,
                            lang = if (text.count { it.code in 0x0900..0x097F } > text.length * 0.2) "hi" else if (text.isBlank()) "zxx" else "en",
                            itemType = itemType, sourceFile = j.getString("name"), timestamp = ts, pHash = hash,
                            tags = AutoTags.of(text, itemType),
                        )
                        persister.persistImageShell(item)
                        val (metadata, review) = meta.extract(id, text, itemType)
                        persister.persistImageMetadata(id, metadata, review)
                        pictures++
                        rows++
                        val image = j.optString("image")
                        if (photos != null && photoDir != null && image.isNotEmpty()) {
                            val bitmap = BitmapFactory.decodeFile(File(photoDir, image).path)
                            if (bitmap != null && photos.embed(id, hash, bitmap)) vectors++
                            bitmap?.recycle()
                        }
                    }
                }
                onItem(n)
            }
        }
        KeywordIndexFill(db, bm25).fill()
        return Loaded(documents, rows, pictures, vectors, System.currentTimeMillis() - t0)
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null

    companion object {
        fun cases(file: File, dates: BenchDates): List<JSONObject> = file.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { JSONObject(it).apply { put("query", dates.resolve(getString("query"))) } }.toList()
        }

        fun ids(array: JSONArray?): List<String> = if (array == null) emptyList() else List(array.length()) { array.getString(it) }

        suspend fun <T> Flow<T>.all(): List<T> = toList()
    }
}
