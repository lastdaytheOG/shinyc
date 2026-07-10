package com.amar.vault.ai.core

import com.amar.vault.ui.renderengine.models.RichSavedItem
import com.amar.vault.ai.models.AIConfidence

data class ProcessorResult(
    val type: String,
    val rawValue: String,
    val confidence: AIConfidence
)

interface AIProcessor {
    val id: String
    val taskType: String
    suspend fun process(item: RichSavedItem): ProcessorResult
}

abstract class BaseAIProcessor(override val id: String, override val taskType: String) : AIProcessor {
    override suspend fun process(item: RichSavedItem): ProcessorResult {
        val prompt = PromptBuilder.build(item, taskType)
        val executor = ModelRouter.route(taskType)
        
        val rawOutput = executor.execute(prompt)
        
        val confidence = ConfidenceEngine.generate(
            processorId = id,
            source = if (executor is LocalModelExecutor) "Local" else "Cloud",
            rawOutput = rawOutput
        )
        
        return ProcessorResult(
            type = taskType,
            rawValue = rawOutput,
            confidence = confidence
        )
    }
}

class TagProcessor : BaseAIProcessor("TagProcessor", "tags")
class CategoryProcessor : BaseAIProcessor("CategoryProcessor", "category")
class SummaryProcessor : BaseAIProcessor("SummaryProcessor", "summary")

object ConfidenceEngine {
    fun generate(processorId: String, source: String, rawOutput: String): AIConfidence {
        // In reality, this would read logprobs from the LLM if available.
        // For now, we simulate confidence based on output length/formatting heuristics.
        val baseScore = if (rawOutput.isNotBlank() && rawOutput != "mock_local_result") 0.95 else 0.50
        
        return AIConfidence(
            score = baseScore,
            processorId = processorId,
            modelVersion = "v1.0",
            source = source
        )
    }
}
