package com.amar.vault

import android.content.Context
import android.content.SharedPreferences

class RuntimeStatsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("runtime_stats", Context.MODE_PRIVATE)

    fun recordMetrics(modelId: String, metrics: NativeLlamaEngine.RuntimeMetrics) {
        val currentTps = prefs.getFloat("${modelId}_tps", 0f)
        val newTps = if (currentTps == 0f) metrics.tps else (currentTps * 0.7f + metrics.tps * 0.3f)
        
        val currentTtft = prefs.getLong("${modelId}_ttft", 0L)
        val newTtft = if (currentTtft == 0L) metrics.ttftMs else ((currentTtft * 0.7) + (metrics.ttftMs * 0.3)).toLong()
        
        prefs.edit()
            .putFloat("${modelId}_tps", newTps)
            .putLong("${modelId}_ttft", newTtft)
            .putLong("${modelId}_last_load_time", metrics.loadTimeMs)
            .putString("last_failure_reason", "NONE")
            .apply()
    }

    fun recordFailure(reason: String) {
        prefs.edit().putString("last_failure_reason", reason).apply()
    }

    fun detectDegradation(modelId: String): Boolean {
        val avgTps = prefs.getFloat("${modelId}_tps", 0f)
        val avgTtft = prefs.getLong("${modelId}_ttft", 0L)

        // Don't alert if we haven't gathered metrics yet
        if (avgTps == 0f) return false 

        val expectedTps = getExpectedTps(modelId)
        val expectedTtft = getExpectedTtft(modelId)

        return (avgTps > 0 && avgTps < expectedTps) || (avgTtft > expectedTtft)
    }

    private fun getExpectedTps(modelId: String): Float = when (modelId) {
        "qwen-1.5b" -> 4.0f
        "qwen-3b" -> 2.0f
        "gemma-2b" -> 2.5f
        else -> 2.0f
    }

    private fun getExpectedTtft(modelId: String): Long = when (modelId) {
        "qwen-1.5b" -> 3000L
        "qwen-3b" -> 5000L
        "gemma-2b" -> 4000L
        else -> 5000L
    }
}
