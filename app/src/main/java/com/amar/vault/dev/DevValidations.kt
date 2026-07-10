package com.amar.vault.dev

import android.content.Context
import com.amar.vault.ModelManager
import com.amar.vault.VaultDatabase
import com.amar.vault.VaultItem

/**
 * One-click AI tests for Developer Tools.
 *
 * Every action drives the REAL production path — [ModelManager] for lifecycle, the shared
 * [com.amar.vault.retrieval.LanguageModel] for inference, and [com.amar.vault.RagService] for
 * Summary / Chat / RAG. No inference pipeline is duplicated. Results are short human-readable
 * strings for the panel.
 */
object DevValidations {

    suspend fun testRag(context: Context): String {
        val items = sampleItems(context, 3)
        if (items.isEmpty()) return "No indexed documents to test with."
        val out = DevServices.rag(context).executeRag("Summarize the key information.", items)
        return if (out.isBlank()) "RAG returned empty (no active/loaded model)."
               else "RAG over ${items.size} docs → ${out.take(240)}"
    }

    suspend fun testSummary(context: Context): String {
        val item = sampleItems(context, 1).firstOrNull() ?: return "No indexed documents."
        val out = DevServices.rag(context).summarizeDocument(item)
        return if (out.isBlank()) "Summary empty (no active/loaded model)."
               else "Summary of '${item.sourceFile}' → ${out.take(240)}"
    }

    suspend fun testChat(context: Context): String {
        val item = sampleItems(context, 1).firstOrNull() ?: return "No indexed documents."
        val out = DevServices.rag(context).executeRag("What is this document about?", listOf(item))
        return if (out.isBlank()) "Chat empty (no active/loaded model)."
               else "Chat grounded on '${item.sourceFile}' → ${out.take(240)}"
    }

    suspend fun currentModel(context: Context): String {
        val m = ModelManager.getInstance(context).getEnabledModel() ?: return "No active model."
        val loaded = DevServices.languageModel(context).isLoaded()
        return "Active: ${m.displayName} (${m.fileName}) status=${m.status} nativeLoaded=$loaded"
    }

    private suspend fun sampleItems(context: Context, n: Int): List<VaultItem> =
        VaultDatabase.get(context).vaultDao().getAll().take(n)
}
