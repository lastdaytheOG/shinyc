package com.amar.vault.vectors

import com.amar.vault.ItemType
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog
import com.amar.vault.photos.PhotoIndex
import com.amar.vault.photos.PhotoVectors
import com.amar.vault.photos.QueryEncoder
import androidx.room.withTransaction
import java.security.MessageDigest

object TextPassages {
    const val MIN_CHARS = 12

    fun title(row: RowToEmbed): String? {
        val type = ItemType.ofStored(row.itemType)
        if (row.parentDocumentId == null && type != null && type.isImage) return null
        val name = (row.title ?: row.sourceFile).trim()
        if (name.isEmpty()) return null
        return name.substringBeforeLast('.', name).replace('_', ' ').trim().ifEmpty { null }
    }

    fun text(row: RowToEmbed): String = row.ocrText.trim()

    fun hash(title: String?, text: String, maxTokens: Int = QueryEncoder.PASSAGE_TOKENS): String {
        val d = MessageDigest.getInstance("SHA-256").digest((QueryEncoder.passageText(title, text) + "#" + maxTokens).toByteArray(Charsets.UTF_8))
        return d.take(10).joinToString("") { "%02x".format(it) }
    }

    fun hash(row: RowToEmbed): String = hash(title(row), text(row))

    fun of(item: com.amar.vault.VaultItem): RowToEmbed = RowToEmbed(item.id, item.ocrText, item.sourceFile, item.title,
        item.parentDocumentId, item.pageNum, item.chunkIndex, item.itemType.stored, item.timestamp)
}

interface PassageSpace {
    val textModelId: String?
    suspend fun textIndex(): PhotoIndex?
    suspend fun embedPassage(title: String?, text: String, maxTokens: Int = QueryEncoder.PASSAGE_TOKENS): FloatArray?
    fun putText(rowId: String, bytes: ByteArray, scale: Float, hash: String)
    fun forgetRows(rowIds: Collection<String>)
    fun setTextEligible(count: Int)
}

class TextVectorIndexer(private val db: VaultDatabase, private val search: PassageSpace) {

    class Pass(val embedded: Int, val stale: Int, val orphans: Int, val remaining: Int, val ms: Long)

    suspend fun sweep(model: String): Pair<Int, Int> {
        val dao = db.textVectorDao()
        val orphans = dao.deleteOrphans()
        db.factDao().deleteOrphans()
        db.coverageDao().deleteOrphans()
        val stored = dao.hashesOf(model).filter { it.part == 0 }
        var stale = 0
        stored.chunked(400).forEach { some ->
            val rows = dao.rows(some.map { it.rowId }).associateBy { it.id }
            val bad = some.filter { s -> rows[s.rowId]?.let { TextPassages.hash(it) != s.textHash } ?: true }.map { it.rowId }
            if (bad.isNotEmpty()) {
                dao.deleteRows(bad)
                search.forgetRows(bad)
                stale += bad.size
            }
        }
        return stale to orphans
    }

    suspend fun run(tier: Int, maxRows: Int = Int.MAX_VALUE, shouldStop: () -> Boolean = { false }, onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Pass {
        val t0 = System.currentTimeMillis()
        val model = search.textModelId ?: return Pass(0, 0, 0, -1, 0)
        search.textIndex() ?: return Pass(0, 0, 0, -1, 0)
        val (stale, orphans) = sweep(model)
        val dao = db.textVectorDao()
        var embedded = 0
        val touched = LinkedHashSet<String>()
        loop@ while (embedded < maxRows && !shouldStop()) {
            val batch = dao.rowsWithout(model, tier, TextPassages.MIN_CHARS, BATCH)
            if (batch.isEmpty()) break
            val made = ArrayList<TextVector>(batch.size)
            for (row in batch) {
                if (shouldStop() || embedded + made.size >= maxRows) break
                val title = TextPassages.title(row)
                val text = TextPassages.text(row)
                val vector = search.embedPassage(title, text) ?: break@loop
                val (bytes, scale) = PhotoVectors.quantize(vector)
                made += TextVector(row.id, model, 0, TextPassages.hash(title, text), bytes, scale, System.currentTimeMillis())
                touched += row.parentDocumentId ?: row.id
            }
            if (made.isEmpty()) break
            val current = dao.rows(made.map { it.rowId }).associateBy { it.id }
            val fresh = made.filter { v -> current[v.rowId]?.let { TextPassages.hash(it) == v.textHash } == true }
            db.withTransaction { dao.upsertAll(fresh) }
            fresh.forEach { search.putText(it.rowId, it.vector, it.scale, it.textHash) }
            embedded += made.size
            if (touched.size >= 50) {
                db.coverageDao().refresh(touched.toList(), model, TextPassages.MIN_CHARS, System.currentTimeMillis())
                touched.clear()
            }
            onProgress(embedded, dao.countWithout(model, tier, TextPassages.MIN_CHARS))
        }
        if (touched.isNotEmpty()) db.coverageDao().refresh(touched.toList(), model, TextPassages.MIN_CHARS, System.currentTimeMillis())
        val remaining = dao.countWithout(model, tier, TextPassages.MIN_CHARS)
        search.setTextEligible(dao.rowsWithVector(model) + dao.countWithout(model, 2, TextPassages.MIN_CHARS))
        val ms = System.currentTimeMillis() - t0
        VaultLog.i(TAG, "tier $tier: embedded $embedded rows in $ms ms, $remaining left, $stale stale, $orphans orphans")
        return Pass(embedded, stale, orphans, remaining, ms)
    }

    suspend fun refreshCoverage() {
        val model = search.textModelId ?: return
        db.coverageDao().refreshAll(model, TextPassages.MIN_CHARS, System.currentTimeMillis())
    }

    companion object {
        private const val TAG = "TextVectors"
        private const val BATCH = 16
    }
}
