package com.amar.vault.planning

/**
 * Pure planner data model. This package deliberately has no Android, Room, OCR, or model
 * dependencies so plans can be replayed and tested on the JVM before workers exist.
 */

enum class SourceType { PDF, TEXT, IMAGE, OFFICE, ARCHIVE, UNKNOWN }

enum class RetrievalMode { LEXICAL, SEMANTIC, HYBRID }

enum class Priority { FOREGROUND_READY, FOREGROUND_COMPLETE, BACKGROUND_FRESH, IDLE_BACKFILL }

enum class ThermalState { NORMAL, WARM, HOT, CRITICAL }

enum class PlanFamily {
    REUSE,
    TEXT_ONLY,
    TEXT_SEMANTIC,
    OCR_CASCADE,
    OCR_CASCADE_SEMANTIC,
    LEXICAL_NOW_SEMANTIC_DEFERRED,
    SAFE_QUARANTINE,
}

enum class OperatorId {
    PUBLISH_REUSE,
    READ_NATIVE_TEXT,
    PRELIGHT_IMAGE,
    OCR_LIGHT,
    OCR_ESCALATE,
    TOKENIZE_AND_POSTINGS,
    EMBED_DOCUMENT,
    ENQUEUE_SEMANTIC,
    COMMIT_MANIFEST,
}

enum class WorkerClass { MANIFEST, PARSER, RASTER, OCR_LIGHT, OCR_HEAVY, TOKENIZER, EMBEDDING, COMMIT }

enum class FallbackTrigger {
    QUALITY_PREDICATE_FAILED,
    TIMEOUT,
    RESOURCE_DENIED,
    CAPABILITY_LOST,
    INPUT_INVALID,
    ARTIFACT_CONFLICT,
    WORKER_ERROR,
}

enum class SemanticCoverage { NOT_REQUIRED, COMPLETE, DEFERRED }

data class SourceIdentity(
    val sourceId: String,
    val contentFingerprint: String,
    val sourceType: SourceType,
    val byteLength: Long,
)

data class AnalyzerEvidence(
    val featureVersion: String,
    val featureDigest: String,
    val pageCount: Int = 1,
    val hasValidNativeText: Boolean = false,
    val nativeTextComplete: Boolean = false,
    val nativeTextConfidence: Double = 0.0,
    val visualTextLikely: Boolean = false,
    val estimatedPixels: Long = 0L,
    val estimatedTextPages: Int = 0,
    val script: String = "unknown",
    val isMalformed: Boolean = false,
)

data class ArtifactState(
    val manifestGeneration: Long? = null,
    val exactReadyModes: Set<RetrievalMode> = emptySet(),
    val operatorVersion: String? = null,
    val modelVersion: String? = null,
    val certificateValid: Boolean = false,
)

data class CapabilitySnapshot(
    val capabilityEpoch: Long,
    val nativeFreeBytes: Long,
    val cpuTokens: Int,
    val heavyOcrSlots: Int,
    val embeddingSlots: Int,
    val thermal: ThermalState = ThermalState.NORMAL,
    val charging: Boolean = false,
    val foreground: Boolean = false,
    val availableOperators: Set<OperatorId> = OperatorId.entries.toSet(),
    val residentModels: Set<String> = emptySet(),
)

data class ResourceBudget(
    val maxLatencyMs: Long = 15 * 60 * 1000L,
    val maxPeakBytes: Long = Long.MAX_VALUE,
    val maxWriteBytes: Long = Long.MAX_VALUE,
    val maxPixels: Long = Long.MAX_VALUE,
    val maxEscalations: Int = 2,
)

data class PlannerPolicy(
    val plannerVersion: String = "planner-0.1",
    val policyVersion: String = "policy-0.1",
    val costModelVersion: String = "cost-0.1",
    val requiredModes: Set<RetrievalMode> = setOf(RetrievalMode.LEXICAL),
    val priority: Priority = Priority.BACKGROUND_FRESH,
    val minimumQualityLowerBound: Double = 0.99,
    val allowDeferredSemantic: Boolean = true,
    val requireSemanticCompleteBeforePublish: Boolean = false,
    val budget: ResourceBudget = ResourceBudget(),
    val memorySafetyFraction: Double = 0.20,
    val latencyWeight: Double = 1.0,
    val tailRiskWeight: Double = 1.0,
    val energyWeight: Double = 1.0,
    val memoryWeight: Double = 1.0,
    val writeWeight: Double = 1.0,
    val coldStartWeight: Double = 1.0,
    val failureWeight: Double = 1.0,
)

data class CostEstimate(
    val latencyP50Ms: Long,
    val latencyP95Ms: Long,
    val energyProxy: Double,
    val peakBytesP95: Long,
    val writeBytes: Long,
    val coldStartRisk: Double,
    val failureRisk: Double,
    val qualityLowerBound: Double,
    val escalations: Int = 0,
) {
    fun feasible(policy: PlannerPolicy, capabilities: CapabilitySnapshot): Boolean {
        val availableAfterSafety = (capabilities.nativeFreeBytes *
            (1.0 - policy.memorySafetyFraction).coerceIn(0.0, 1.0)).toLong()
        return latencyP95Ms <= policy.budget.maxLatencyMs &&
            peakBytesP95 <= policy.budget.maxPeakBytes &&
            peakBytesP95 <= availableAfterSafety &&
            writeBytes <= policy.budget.maxWriteBytes &&
            escalations <= policy.budget.maxEscalations &&
            qualityLowerBound >= policy.minimumQualityLowerBound
    }

    fun score(policy: PlannerPolicy): Double =
        policy.latencyWeight * latencyP95Ms +
            policy.tailRiskWeight * (failureRisk * latencyP95Ms) +
            policy.energyWeight * energyProxy +
            policy.memoryWeight * (peakBytesP95 / 1_000_000.0) +
            policy.writeWeight * (writeBytes / 1_000_000.0) +
            policy.coldStartWeight * coldStartRisk * 100.0 +
            policy.failureWeight * failureRisk * 100.0
}

data class OperatorNode(
    val id: OperatorId,
    val worker: WorkerClass,
    val dependsOn: List<OperatorId> = emptyList(),
    val conditional: Boolean = false,
)

data class FallbackRule(
    val trigger: FallbackTrigger,
    val nextFamily: PlanFamily,
    val maxAttempts: Int = 1,
)

data class ExecutionPlan(
    val planId: String,
    val family: PlanFamily,
    val nodes: List<OperatorNode>,
    val fallbackChain: List<FallbackRule>,
    val estimate: CostEstimate,
    val semanticCoverage: SemanticCoverage,
    val qualityContract: Double,
    val inputDigest: String,
    val determinismSeed: Long,
)

data class PlannerInput(
    val source: SourceIdentity,
    val evidence: AnalyzerEvidence,
    val artifacts: ArtifactState = ArtifactState(),
    val capabilities: CapabilitySnapshot,
    val policy: PlannerPolicy = PlannerPolicy(),
    val inputDigest: String = "",
    val historicalEstimates: Map<PlanFamily, CostEstimate> = emptyMap(),
)

interface PlannerCostModel {
    val version: String
    fun estimate(family: PlanFamily, input: PlannerInput): CostEstimate
}

/** Conservative, deterministic defaults. Production values must come from measured telemetry. */
class DefaultPlannerCostModel(override val version: String = "cost-0.1") : PlannerCostModel {
    override fun estimate(family: PlanFamily, input: PlannerInput): CostEstimate =
        input.historicalEstimates[family] ?: when (family) {
            PlanFamily.REUSE -> CostEstimate(2, 5, 0.1, 1_000_000, 4_096, 0.0, 0.001, 1.0)
            PlanFamily.TEXT_ONLY -> CostEstimate(80, 250, 1.0, 8_000_000, 128_000, 0.0, 0.01, 1.0)
            PlanFamily.TEXT_SEMANTIC -> CostEstimate(850, 3_500, 30.0, 80_000_000, 2_000_000, 0.2, 0.02, 1.0)
            PlanFamily.OCR_CASCADE -> CostEstimate(900, 4_000, 50.0, 180_000_000, 2_000_000, 0.1, 0.04, 0.995)
            PlanFamily.OCR_CASCADE_SEMANTIC -> CostEstimate(1_800, 7_000, 85.0, 220_000_000, 4_000_000, 0.2, 0.05, 0.995)
            PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED -> CostEstimate(100, 400, 2.0, 12_000_000, 256_000, 0.0, 0.01, 1.0)
            PlanFamily.SAFE_QUARANTINE -> CostEstimate(10, 40, 0.1, 2_000_000, 8_192, 0.0, 0.0, 1.0)
        }
}

sealed class PlannerResult {
    data class Planned(val plan: ExecutionPlan, val decision: PlannerDecision) : PlannerResult()
    data class Refused(val refusal: PlannerRefusal, val decision: PlannerDecision) : PlannerResult()
}

data class CandidateDecision(
    val family: PlanFamily,
    val feasible: Boolean,
    val score: Double?,
    val estimate: CostEstimate,
    val rejectedBecause: String? = null,
)

data class PlannerDecision(
    val decisionId: String,
    val inputDigest: String,
    val plannerVersion: String,
    val policyVersion: String,
    val costModelVersion: String,
    val capabilityEpoch: Long,
    val candidates: List<CandidateDecision>,
    val selectedFamily: PlanFamily?,
    val decisionLatencyMs: Long,
    val deterministic: Boolean,
    val seed: Long,
)

data class PlannerRefusal(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val safePartialFamily: PlanFamily? = null,
)
