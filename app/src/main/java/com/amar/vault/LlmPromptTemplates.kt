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