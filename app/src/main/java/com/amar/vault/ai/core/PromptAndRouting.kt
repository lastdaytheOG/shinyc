package com.amar.vault.ai.core

import com.amar.vault.ui.renderengine.models.RichSavedItem

data class ProcessedPrompt(
    val systemPrompt: String,
    val userContent: String,
    val maxTokens: Int
)

object PromptBuilder {
    /**
     * Cleans up noise, enforces token limits, and formats data for specialized processors.
     */
    fun build(item: RichSavedItem, taskType: String): ProcessedPrompt {
        val meta = item.rawMetadata
        // Assemble text aggressively stripping noise
        val rawContent = buildString {
            appendLine("Title: ${item.title}")
            meta.description?.value?.let { appendLine("Desc: $it") }
            item.rawStashItem.userNote?.let { appendLine("Notes: $it") }
            // Future: append OCR text here, truncating to avoid token overflow
        }.trim()
        
        // Truncate to reasonable token approximation (e.g., ~2000 chars)
        val safeContent = rawContent.take(2000)

        val systemPrompt = when (taskType) {
            "tags" -> "You are a precise librarian. Generate up to 5 highly relevant, single-word tags for the provided content. Output as a comma-separated list only."
            "summary" -> "You are an expert analyst. Provide a one-sentence summary explaining why this content is valuable."
            "category" -> "Categorize this content into exactly one of: Technology, Programming, Fitness, Health, Recipes, Travel, Finance, Shopping, Music, Education, Art, Science. Output only the category name."
            else -> "Analyze the content."
        }

        return ProcessedPrompt(
            systemPrompt = systemPrompt,
            userContent = safeContent,
            maxTokens = 50 // Keep outputs tiny and fast
        )
    }
}

interface AIModelExecution {
    suspend fun execute(prompt: ProcessedPrompt): String
}

object ModelRouter {
    /**
     * Determines which model to use based on task complexity or availability.
     * Keeps processors completely blind to whether it's local Gemma, cloud GPT, etc.
     */
    fun route(taskType: String): AIModelExecution {
        return when (taskType) {
            "summary" -> CloudModelExecutor // Summaries might need better reasoning
            "tags", "category" -> LocalModelExecutor // Tags are easy, keep it local and private
            else -> LocalModelExecutor
        }
    }
}

// Stubs for future implementation
object LocalModelExecutor : AIModelExecution {
    override suspend fun execute(prompt: ProcessedPrompt): String {
        // e.g. Llama.cpp binding
        return "mock_local_result"
    }
}

object CloudModelExecutor : AIModelExecution {
    override suspend fun execute(prompt: ProcessedPrompt): String {
        // e.g. Retrofit call to Claude/GPT
        return "mock_cloud_result"
    }
}
