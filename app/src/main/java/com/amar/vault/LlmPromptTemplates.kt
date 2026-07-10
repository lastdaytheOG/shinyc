package com.amar.vault

/**
 * Prompt templates for the on-device Qwen 2.5 LLM.
 *
 * Qwen 2.5 uses the ChatML format:
 *   <|im_start|>system\n{system_message}<|im_end|>\n
 *   <|im_start|>user\n{user_message}<|im_end|>\n
 *   <|im_start|>assistant\n
 *
 * The model generates until it emits <|im_end|> (end-of-turn token).
 *
 * IMPORTANT: These prompts are engineered for the INT4/INT8 quantized
 * Qwen 2.5 0.5B and 1.5B models. They use short, direct instructions
 * because smaller models follow brief prompts more reliably than verbose ones.
 */
object LlmPromptTemplates {

    // =========================================================================
    // Ghost Organizer — Folder Naming
    // =========================================================================

    /**
     * Generate a 2-3 word folder name from OCR text of representative screenshots.
     *
     * @param ocrTexts  OCR text from the 3 centroid-nearest screenshots
     * @return          Complete prompt ready for inference
     */
    fun folderNaming(ocrTexts: List<String>): String {
        // Cap each OCR sample to prevent context overflow on small models
        val combinedText = ocrTexts.joinToString("\n---\n") { it.take(300) }

        return buildChatML(
            system = "You are an AI organizing a user's digital vault. " +
                    "Generate a short, 2-to-3 word title for the folder these documents belong in. " +
                    "Reply ONLY with the title. No explanation, no punctuation, no quotes.",
            user = combinedText
        )
    }

    // =========================================================================
    // Phase 7 — RAG Chatbot (Foundation)
    // =========================================================================

    /**
     * RAG-enhanced Q&A over the user's vault.
     * Context is injected from the vector search results.
     *
     * @param question  User's natural language question
     * @param context   Retrieved document text from vector search
     * @return          Complete prompt ready for inference
     */
    fun ragQuery(question: String, context: String): String {
        return buildChatML(
            system = "You are a helpful assistant that answers questions based on the user's documents. " +
                    "Use ONLY the provided context to answer. If the answer is not in the context, say so. " +
                    "Keep answers concise and accurate.",
            user = "Context:\n${context.take(1500)}\n\nQuestion: $question"
        )
    }

    /**
     * Summarize a single document's OCR text.
     *
     * @param ocrText  The full OCR text of a screenshot
     * @return         Complete prompt ready for inference
     */
    fun summarize(ocrText: String): String {
        return buildChatML(
            system = "Summarize the following document in 1-2 sentences. Be concise and factual.",
            user = ocrText.take(2000)
        )
    }

    /**
     * Extract key entities (dates, amounts, names) from a document.
     *
     * @param ocrText  The OCR text to extract from
     * @return         Complete prompt ready for inference
     */
    fun extractEntities(ocrText: String): String {
        return buildChatML(
            system = "Extract key information from this document. " +
                    "List any dates, monetary amounts, names, and reference numbers you find. " +
                    "Format as a short bullet list. If none found, say 'No key entities found.'",
            user = ocrText.take(2000)
        )
    }

    // =========================================================================
    // ChatML Builder
    // =========================================================================

    /**
     * Build a Qwen 2.5 ChatML-formatted prompt.
     *
     * The trailing "<|im_start|>assistant\n" tells the model to begin
     * generating the assistant's response.
     */
    fun buildChatML(system: String, user: String): String {
        return buildString {
            append("<|im_start|>system\n")
            append(system)
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append(user)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }

    /**
     * Build a multi-turn ChatML conversation.
     *
     * @param system   System prompt
     * @param turns    List of (role, content) pairs: "user" or "assistant"
     */
    fun buildMultiTurnChatML(
        system: String,
        turns: List<Pair<String, String>>
    ): String {
        return buildString {
            append("<|im_start|>system\n")
            append(system)
            append("<|im_end|>\n")
            for ((role, content) in turns) {
                append("<|im_start|>$role\n")
                append(content)
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }
}