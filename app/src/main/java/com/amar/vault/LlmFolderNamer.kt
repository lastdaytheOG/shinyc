package com.amar.vault

import android.content.Context
import android.util.Log

/**
 * LLM Folder Naming Service — used by GhostOrganizerWorker.
 *
 * Encapsulates the full lifecycle:
 *   1. Load model into VRAM
 *   2. Generate folder name from OCR text
 *   3. Evict model from VRAM
 *
 * This class ensures the model is ALWAYS unloaded after use, even on failure.
 * Leaving the Qwen 2.5 model loaded (~1.5GB) while the nightly worker is
 * running in the background would get the app killed by Android's LMK.
 *
 * Usage in GhostOrganizerWorker:
 * ```
 * val namer = LlmFolderNamer(applicationContext)
 * val name = namer.generateFolderName(ocrTexts, modelPath)
 * ```
 */
class LlmFolderNamer(
    private val context: Context,
    private val languageModel: com.amar.vault.retrieval.LanguageModel,
) {

    companion object {
        private const val TAG = "LlmFolderNamer"
        private const val MAX_NAME_LENGTH = 30
    }

    /**
     * Generate a folder name using the on-device LLM.
     *
     * Full lifecycle: load → infer → unload. Always unloads, even on error.
     *
     * @param ocrTexts   OCR text from the 3 centroid-nearest representative screenshots
     * @param modelPath  Absolute path to the .gguf model file
     * @return           A 2-3 word folder name, or a heuristic fallback on failure
     */
    suspend fun generateFolderName(
        ocrTexts: List<String>,
        modelPath: String
    ): String {
        if (ocrTexts.isEmpty()) return "Untitled Group"

        return try {
            // Step 1: Load the brain into VRAM
            val loaded = languageModel.loadModel(modelPath)
            if (!loaded) {
                Log.e(TAG, "Failed to load model. Falling back to heuristic.")
                return inferFolderNameHeuristic(ocrTexts)
            }

            try {
                // Step 2: Build the prompt
                val prompt = LlmPromptTemplates.folderNaming(ocrTexts)

                // Step 3: Generate (blocking — we're in a background worker)
                val rawResult = languageModel.generateBlocking(prompt)
                val rawName = rawResult.text

                // Step 4: Sanitize the output
                sanitizeFolderName(rawName)

            } finally {
                // Step 5: ALWAYS evict the model, even if generation threw
                languageModel.unload()
                Log.i(TAG, "Model unloaded after folder naming")
            }

        } catch (e: Exception) {
            Log.e(TAG, "LLM folder naming failed: ${e.message}", e)
            inferFolderNameHeuristic(ocrTexts)
        }
    }

    /**
     * Batch-generate folder names for multiple clusters.
     *
     * Loads the model ONCE, generates all names, then unloads.
     * Much more efficient than loading/unloading per cluster.
     *
     * @param clusters   Map of clusterId to list of OCR texts for representatives
     * @param modelPath  Path to .gguf file
     * @return           Map of clusterId to generated folder name
     */
    suspend fun generateFolderNames(
        clusters: Map<Int, List<String>>,
        modelPath: String
    ): Map<Int, String> {
        if (clusters.isEmpty()) return emptyMap()

        val results = mutableMapOf<Int, String>()

        val loaded = languageModel.loadModel(modelPath)
        if (!loaded) {
            Log.e(TAG, "Failed to load model for batch naming. Using heuristics.")
            return clusters.mapValues { (_, ocrTexts) -> inferFolderNameHeuristic(ocrTexts) }
        }

        try {
            for ((clusterId, ocrTexts) in clusters) {
                try {
                    val prompt = LlmPromptTemplates.folderNaming(ocrTexts)
                    val rawResult = languageModel.generateBlocking(prompt)
                    results[clusterId] = sanitizeFolderName(rawResult.text)

                    Log.i(TAG, "Cluster $clusterId → \"${results[clusterId]}\"")
                } catch (e: Exception) {
                    Log.w(TAG, "LLM failed for cluster $clusterId, using heuristic", e)
                    results[clusterId] = inferFolderNameHeuristic(ocrTexts)
                }
            }
        } finally {
            languageModel.unload()
            Log.i(TAG, "Model unloaded after batch naming (${results.size} folders)")
        }

        return results
    }

    // =========================================================================
    // Sanitization
    // =========================================================================

    /**
     * Clean up LLM output to produce a valid folder name.
     * Handles common model quirks: extra quotes, newlines, special tokens.
     */
    private fun sanitizeFolderName(raw: String): String {
        var name = raw
            .trim()
            .replace(Regex("[\"'`]"), "")          // Remove quotes
            .replace(Regex("<\\|.*?\\|>"), "")      // Remove any leaked special tokens
            .replace(Regex("\\s+"), " ")            // Collapse whitespace
            .lines().first()                        // Take only first line
            .trim()

        // Cap length
        if (name.length > MAX_NAME_LENGTH) {
            name = name.take(MAX_NAME_LENGTH).trimEnd()
        }

        // Title case
        name = name.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }

        return name.ifBlank { "Untitled Group" }
    }

    // =========================================================================
    // Heuristic Fallback
    // =========================================================================

    /**
     * Extract the most common meaningful 2-word phrase from OCR text.
     * Used when the LLM is unavailable or fails.
     */
    private fun inferFolderNameHeuristic(ocrTexts: List<String>): String {
        val stopWords = setOf(
            "the", "and", "for", "with", "from", "this", "that", "your", "you",
            "are", "was", "has", "have", "been", "will", "can", "not", "but",
            "all", "any", "its"
        )

        val words = ocrTexts.flatMap { text ->
            text.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && it !in stopWords }
        }

        val freq = mutableMapOf<String, Int>()
        for (w in words) freq[w] = (freq[w] ?: 0) + 1

        val topWords = freq.entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key.replaceFirstChar { c -> c.uppercase() } }

        return if (topWords.isNotEmpty()) topWords.joinToString(" ") else "Untitled Group"
    }
}