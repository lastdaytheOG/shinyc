package com.amar.vault.indexing

import android.content.Context
import androidx.room.withTransaction
import com.amar.vault.ItemType
import com.amar.vault.QrPayloads
import com.amar.vault.TextForTagging
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog

/**
 * Keeps the tags of what is already stored in step with the rules ([AutoTags]).
 *
 * Tags are worked out from an item's stored text, so a change of rules needs nothing read
 * again: every item is tagged afresh from what the database holds. This is done once per
 * version of the rules, when the app starts. The keyword engine indexes the tags, so it is
 * told which items changed ([retagAll]'s `onRetagged`) as they are written.
 *
 * Only `tags` is written — and, for a picture with a QR code, the words that used to be added
 * to its text ([QrText.withoutFormerWords]). A row whose tags come out the same is not touched.
 * Stopped half-way, it runs again from the start next time; doing it twice changes nothing.
 */
class AutoTagUpkeep(private val db: VaultDatabase, context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** What one run did, for the log and the developer screen. */
    data class Report(
        val rulesVersion: Int,
        val ranAt: Long,
        val itemsChecked: Int,
        val itemsChanged: Int,
        /** How many pictures and pages were recognised as each kind, by the kind's name. */
        val recognised: Map<String, Int>,
        val tookMs: Long,
    ) {
        fun summary(): String = buildString {
            append("rules v$rulesVersion; $itemsChecked items checked; $itemsChanged re-tagged; ${tookMs} ms")
            append("; recognised: ")
            append(if (recognised.isEmpty()) "nothing" else recognised.entries.joinToString(", ") { "${it.key} ${it.value}" })
        }
    }

    /** Tags everything again when the stored tags are from other rules; null when they are current. */
    suspend fun retagIfRulesChanged(onRetagged: suspend (List<String>) -> Unit = {}): Report? =
        if (prefs.getInt(KEY_VERSION, 0) == AutoTags.VERSION) null else retagAll(onRetagged)

    /** [onRetagged] is given the ids of the rows just written, a batch or a document at a time. */
    suspend fun retagAll(onRetagged: suspend (List<String>) -> Unit = {}): Report {
        val report = pass(write = true, onRetagged)
        prefs.edit().putInt(KEY_VERSION, AutoTags.VERSION).putString(KEY_REPORT, "${report.ranAt}|${report.summary()}").apply()
        VaultLog.i(TAG, "tagged again: ${report.summary()}")
        return report
    }

    /**
     * How many stored rows the rules would tag differently from how they are tagged now.
     * Nothing is written. It is 0 whenever the stored tags are current, which is what makes
     * tagging at indexing time and tagging again later the same thing.
     */
    suspend fun countOutOfDate(): Int = pass(write = false, onRetagged = {}).itemsChanged

    private suspend fun pass(write: Boolean, onRetagged: suspend (List<String>) -> Unit): Report {
        val started = System.currentTimeMillis()
        var checked = 0
        var changed = 0
        val recognised = sortedMapOf<String, Int>()
        fun note(text: String, codes: Boolean) {
            AutoTags.kinds(text).forEach { recognised.merge(it.name.lowercase(), 1, Int::plus) }
            if (codes) recognised.merge("code", 1, Int::plus)
        }

        // Whole items: pictures, saved links and files. A few hundred at a time, by rowid.
        var after = Long.MIN_VALUE
        while (true) {
            val batch = db.vaultDao().wholeItemsForTagging(after, BATCH)
            if (batch.isEmpty()) break
            // Each row that comes out different: its id, its text if that changes too, its tags.
            val different = ArrayList<Triple<String, String?, String>>()
            for (row in batch) {
                checked++
                val codes = QrPayloads.split(row.qrPayload)
                val text = if (codes.isEmpty()) row.ocrText else QrText.withoutFormerWords(row.ocrText)
                // Pictures are what the indexer tags among whole items. A saved file's own row
                // has no text (its pieces are tagged below), and a saved link or note is
                // stored without tags.
                val tags = if (row.itemType.isImage) AutoTags.of(text, row.itemType, codes) else row.tags
                if (row.itemType.isImage) note(text, codes.isNotEmpty())
                if (text != row.ocrText) different += Triple(row.id, text, tags)
                else if (tags != row.tags) different += Triple(row.id, null, tags)
            }
            changed += different.size
            if (write && different.isNotEmpty()) {
                db.withTransaction {
                    for ((id, newText, tags) in different) {
                        if (newText != null) db.vaultDao().setTextAndTags(id, newText, tags)
                        else db.vaultDao().setTags(listOf(id), tags)
                    }
                }
                onRetagged(different.map { it.first })
            }
            after = batch.last().rowId
        }

        // Documents: a page at a time, each page's pieces together.
        for (document in db.vaultDocumentDao().getAll()) {
            val pieces = db.vaultDao().piecesForTagging(document.id)
            checked += pieces.size
            val paged = document.itemType == ItemType.PDF
            val retagged = HashMap<String, MutableList<String>>()
            for (together in TagUnits.of(pieces) { if (paged) it.pageNum else null }) {
                val text = TagUnits.text(together.map { it.ocrText })
                val tags = AutoTags.of(text, document.itemType)
                note(text, codes = false)
                together.filter { it.tags != tags }.forEach { retagged.getOrPut(tags) { mutableListOf() }.add(it.id) }
            }
            if (retagged.isEmpty()) continue
            changed += retagged.values.sumOf { it.size }
            if (!write) continue
            db.withTransaction {
                for ((tags, ids) in retagged) ids.chunked(IDS_PER_STATEMENT).forEach { db.vaultDao().setTags(it, tags) }
            }
            onRetagged(retagged.values.flatten())
        }

        return Report(AutoTags.VERSION, started, checked, changed, recognised, System.currentTimeMillis() - started)
    }

    companion object {
        private const val TAG = "AutoTagUpkeep"
        private const val PREFS = "auto_tags"
        private const val KEY_VERSION = "rules_version"
        private const val KEY_REPORT = "last_report"
        private const val BATCH = 300
        /** SQLite takes at most 999 values in one statement. */
        private const val IDS_PER_STATEMENT = 500

        /** When everything was last tagged again and what that did; null when it never was. */
        fun lastReport(context: Context): Pair<Long, String>? {
            val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REPORT, null) ?: return null
            val at = saved.substringBefore('|').toLongOrNull() ?: return null
            return at to saved.substringAfter('|')
        }
    }
}
