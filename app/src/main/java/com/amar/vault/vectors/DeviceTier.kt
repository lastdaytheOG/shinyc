package com.amar.vault.vectors

import android.app.ActivityManager
import android.content.Context
import com.amar.vault.VaultLog

enum class SearchTier { CORE, STANDARD, FULL }

object DeviceTier {

    private const val PREFS = "device_tier"
    private const val KEY_PIECE_MS = "piece_ms"
    private const val KEY_MODEL = "model"
    const val PIECE_BUDGET_MS = 500L
    private const val SAMPLE = "Net amount payable Rs 2,438.20 for 312 units consumed in the billing period. " +
        "Pay before the due date to avoid a late payment surcharge. बिजली का बिल हर महीने की पाँच तारीख तक जमा करना होता है। "

    fun totalMemoryGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    fun pieceMs(context: Context): Long? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_PIECE_MS, -1L).takeIf { it >= 0 }

    suspend fun measureOnce(context: Context, space: PassageSpace): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val model = space.textModelId ?: return null
        if (prefs.getString(KEY_MODEL, null) == model && prefs.contains(KEY_PIECE_MS)) return prefs.getLong(KEY_PIECE_MS, -1)
        space.embedPassage("bill", SAMPLE) ?: return null
        val times = (1..10).mapNotNull {
            val t = System.nanoTime()
            space.embedPassage("bill $it", SAMPLE.repeat(2)) ?: return@mapNotNull null
            (System.nanoTime() - t) / 1_000_000
        }.sorted()
        if (times.isEmpty()) return null
        val median = times[times.size / 2]
        prefs.edit().putLong(KEY_PIECE_MS, median).putString(KEY_MODEL, model).apply()
        VaultLog.i("DeviceTier", "a stored piece takes $median ms here")
        return median
    }

    fun tier(context: Context, modelsReady: Boolean): SearchTier {
        if (!modelsReady) return SearchTier.CORE
        val gb = totalMemoryGb(context)
        val ms = pieceMs(context)
        return when {
            gb >= 7.5 && ms != null && ms <= PIECE_BUDGET_MS / 2 -> SearchTier.FULL
            gb >= 5.5 -> SearchTier.STANDARD
            else -> SearchTier.CORE
        }
    }

    fun embedsEveryPiece(context: Context): Boolean {
        val ms = pieceMs(context) ?: return true
        return ms <= PIECE_BUDGET_MS
    }
}
