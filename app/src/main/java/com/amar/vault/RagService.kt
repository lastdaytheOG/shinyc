package com.amar.vault

import android.content.Context
import com.amar.vault.retrieval.LanguageModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class RagService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val languageModel: LanguageModel,
) {

    /**
     * Executes the grounded RAG workflow.
     * 1. OCR Cleanup
     * 2. Source Truncation
     * 3. Context Assembly
     * 4. Prompt Building
     * 5. Model Invocation
     */
    suspend fun executeRag(query: String, sources: List<VaultItem>): String = withContext(Dispatchers.IO) {
        val modelManager = ModelManager.getInstance(context)
        val activeModel = modelManager.getEnabledModel()
        if (activeModel != null && activeModel.status == "READY" && !languageModel.isLoaded()) {
            val modelFile = java.io.File(context.filesDir, "models/${activeModel.fileName}")
            if (modelFile.exists()) {
                val success = languageModel.loadModel(modelFile.absolutePath)
                android.util.Log.i("RagService", "Auto-loaded enabled model: ${activeModel.modelId} success=$success")
            }
        }
        val isModelReady = activeModel != null && activeModel.status == "READY" && languageModel.isLoaded()

        if (!isModelReady) {
            return@withContext "" // Return empty string so ViewModel can route to smart fallback search answer
        }

        val contextBlock = assembleContext(sources)
        val prompt = if (contextBlock.isNotBlank()) {
            LlmPromptTemplates.buildChatML(
                system = "You are an assistant answering questions about the user's vault.\n" +
                        "Use ONLY information present in the provided context.\n" +
                        "If the answer is not present in the context, explicitly state that the information was not found.\n" +
                        "Never infer missing information.\n" +
                        "Never guess dates, prices, names, quantities, merchants, or events.\n" +
                        "Quote supporting evidence when possible.\n" +
                        "Keep answers concise and factual.",
                user = "Context:\n$contextBlock\n\nQuestion: $query"
            )
        } else {
            LlmPromptTemplates.buildChatML(
                system = "You are an assistant answering questions about the user's vault. Keep answers concise and factual.",
                user = query
            )
        }

        val result = languageModel.generateBlocking(prompt)
        val statsManager = RuntimeStatsManager(context)

        when (result.failureReason) {
            NativeLlamaEngine.FailureReason.NONE -> {
                result.metrics?.let { statsManager.recordMetrics(activeModel.modelId, it) }
                return@withContext result.text
            }
            NativeLlamaEngine.FailureReason.TIMEOUT -> {
                statsManager.recordFailure("TIMEOUT")
                return@withContext "AI took too long."
            }
            NativeLlamaEngine.FailureReason.OOM -> {
                statsManager.recordFailure("OOM")
                return@withContext "Your device doesn't have enough memory for this AI."
            }
            NativeLlamaEngine.FailureReason.JNI_FAILURE -> {
                statsManager.recordFailure(result.failureReason.name)
                // P1: Non-recoverable failure. Do NOT reload.
                return@withContext "AI couldn't start."
            }
            NativeLlamaEngine.FailureReason.LOAD_FAILED -> {
                statsManager.recordFailure(result.failureReason.name)
                languageModel.unload() 

                // P1: Recoverable failure. Attempt 1 retry.
                val modelFile = java.io.File(context.filesDir, "models/${activeModel.fileName}")
                if (modelFile.exists()) {
                    val reloaded = languageModel.loadModel(modelFile.absolutePath)
                    if (reloaded) {
                        val retryResult = languageModel.generateBlocking(prompt)
                        if (retryResult.failureReason == NativeLlamaEngine.FailureReason.NONE) {
                            retryResult.metrics?.let { statsManager.recordMetrics(activeModel.modelId, it) }
                            return@withContext retryResult.text
                        }
                    }
                }
                return@withContext "Download may be corrupted."
            }
            NativeLlamaEngine.FailureReason.CANCELLED -> {
                statsManager.recordFailure("CANCELLED")
                return@withContext ""
            }
        }
    }

    /**
     * Read-only single-document summary. Reuses the existing grounded RAG path verbatim with the
     * selected item as the ONLY source — so context is exactly this document's indexed OCR/extracted
     * text (no cross-document retrieval), the prompt/model execution are unchanged, and an absent
     * model yields "" (the UI shows an unavailable message). No new AI pipeline, no persistence.
     */
    suspend fun summarizeDocument(item: VaultItem): String =
        executeRag("Summarize this document in 2-3 clear, factual sentences.", listOf(item))

    fun ocrCleanup(text: String): String {
        return text
            .substringBefore("\n[") // Remove smart tags suffix
            .replace(Regex("<\\|im_start\\|>", RegexOption.IGNORE_CASE), "") // Stripping ChatML tokens to prevent injection
            .replace(Regex("<\\|im_end\\|>", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun sourceTruncation(text: String, maxLength: Int = 1000): String {
        return text.take(maxLength)
    }

    private fun assembleContext(sources: List<VaultItem>): String {
        return sources.take(3).mapIndexed { index, source ->
            val cleaned = ocrCleanup(source.ocrText)
            val truncated = sourceTruncation(cleaned, 1000)
            "Source ${index + 1}:\n$truncated"
        }.joinToString("\n\n")
    }
}
