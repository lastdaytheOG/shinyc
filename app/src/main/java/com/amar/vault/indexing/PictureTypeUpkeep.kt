package com.amar.vault.indexing

import android.content.Context
import androidx.room.withTransaction
import com.amar.vault.ItemType
import com.amar.vault.PictureFacts
import com.amar.vault.PictureKind
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultLog

/**
 * Puts right what the pictures already in the vault are called.
 *
 * Until [PictureKind] decided it, a picture was a screenshot or a photo according to which
 * part of the app had indexed it: the same screenshot was a "photo" from the bulk scan and a
 * "screenshot" from the nightly one. Each stored picture is looked at again here — what its
 * file is called and the folder it is in, asked of the gallery — and given the type the rule
 * gives it. An item stored as something else that is in fact a picture (a shared picture file
 * whose sender did not say what it was) becomes one.
 *
 * Done once per version of the rule, when the app starts. Only `itemType` is written, and the
 * SOURCE_TYPE fact that repeats it. A picture the gallery no longer has keeps the type it has:
 * there is nothing left to tell by.
 */
class PictureTypeUpkeep(
    private val db: VaultDatabase,
    context: Context,
    /** Every picture in the gallery by its id. Asked once per run. */
    private val gallery: () -> Map<Long, PictureFacts> = { PictureLocator(context.applicationContext).gallery() },
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Report(
        val ruleVersion: Int,
        val ranAt: Long,
        val picturesChecked: Int,
        /** "photo → screenshot" and how many. */
        val changed: Map<String, Int>,
        /** Pictures the gallery no longer has; left as they were. */
        val notInGallery: Int,
    ) {
        val changedCount: Int get() = changed.values.sum()

        fun summary(): String = buildString {
            append("rule v$ruleVersion; $picturesChecked pictures checked; $changedCount renamed")
            if (changed.isNotEmpty()) append(" (" + changed.entries.joinToString(", ") { "${it.key}: ${it.value}" } + ")")
            if (notInGallery > 0) append("; $notInGallery no longer in the gallery, left as they were")
        }
    }

    /** Looks at every stored picture again when the rule has changed; null when it has not. */
    suspend fun retypeIfRuleChanged(onRetyped: suspend (List<String>) -> Unit = {}): Report? =
        if (prefs.getInt(KEY_VERSION, 0) == PictureKind.VERSION) null else retypeAll(onRetyped)

    /** [onRetyped] is given the ids of the rows just written, a batch at a time. */
    suspend fun retypeAll(onRetyped: suspend (List<String>) -> Unit = {}): Report {
        val started = System.currentTimeMillis()
        val inGallery = gallery()
        var checked = 0
        var missing = 0
        val changed = sortedMapOf<String, Int>()

        var after = Long.MIN_VALUE
        while (true) {
            val batch = db.vaultDao().wholeItemsForTyping(after, BATCH)
            if (batch.isEmpty()) break
            val retyped = ArrayList<Pair<String, ItemType>>()
            for (row in batch) {
                if (!row.itemType.isImage && !PictureKind.isPicture(row.mimeType, row.uri, row.sourceFile)) continue
                // A document is never a picture, whatever its file is called.
                if (row.itemType.isDocument) continue
                checked++
                val galleryId = PictureLocator.galleryId(row.uri)
                val known = galleryId?.let(inGallery::get)
                if (galleryId != null && known == null) missing++
                val kind = when {
                    // The gallery says what it is called and where it is: that decides, both ways.
                    known != null -> PictureKind.of(known)
                    // Otherwise only its stored name is left. That can make it a screenshot; it
                    // cannot take that away from a picture that was told to be one by more.
                    PictureKind.of(row.sourceFile) == ItemType.SCREENSHOT -> ItemType.SCREENSHOT
                    row.itemType.isImage -> row.itemType
                    else -> ItemType.PHOTO
                }
                if (kind != row.itemType) {
                    retyped += row.id to kind
                    changed.merge("${row.itemType.stored} → ${kind.stored}", 1, Int::plus)
                }
            }
            if (retyped.isNotEmpty()) {
                db.withTransaction {
                    for ((id, type) in retyped) {
                        db.vaultDao().setItemType(id, type)
                        db.vaultMetadataDao().setSourceType(id, type.stored.uppercase())
                    }
                }
                onRetyped(retyped.map { it.first })
            }
            after = batch.last().rowId
        }

        val report = Report(PictureKind.VERSION, started, checked, changed, missing)
        val edit = prefs.edit().putString(KEY_REPORT, "${report.ranAt}|${report.summary()}")
        // With no gallery to ask — the app may not yet be allowed to see it — the pictures
        // kept there could not be told; they are looked at again next time.
        if (inGallery.isNotEmpty() || missing == 0) edit.putInt(KEY_VERSION, PictureKind.VERSION)
        edit.apply()
        VaultLog.i(TAG, "pictures looked at again: ${report.summary()}")
        return report
    }

    companion object {
        private const val TAG = "PictureTypeUpkeep"
        private const val PREFS = "picture_kind"
        private const val KEY_VERSION = "rule_version"
        private const val KEY_REPORT = "last_report"
        private const val BATCH = 500

        /** When stored pictures were last looked at again and what that did; null when never. */
        fun lastReport(context: Context): Pair<Long, String>? {
            val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_REPORT, null) ?: return null
            val at = saved.substringBefore('|').toLongOrNull() ?: return null
            return at to saved.substringAfter('|')
        }
    }
}
