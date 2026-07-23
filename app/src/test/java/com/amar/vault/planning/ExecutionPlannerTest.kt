package com.amar.vault.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPlannerTest {
    private val capabilities = CapabilitySnapshot(
        capabilityEpoch = 7,
        nativeFreeBytes = 1_000_000_000L,
        cpuTokens = 4,
        heavyOcrSlots = 1,
        embeddingSlots = 1,
    )

    private fun input(
        sourceType: SourceType = SourceType.PDF,
        native: Boolean = false,
        complete: Boolean = native,
        required: Set<RetrievalMode> = setOf(RetrievalMode.LEXICAL),
        artifacts: ArtifactState = ArtifactState(),
        estimates: Map<PlanFamily, CostEstimate> = emptyMap(),
        policy: PlannerPolicy = PlannerPolicy(requiredModes = required),
    ) = PlannerInput(
        source = SourceIdentity("doc", "bytes-1", sourceType, 1000),
        evidence = AnalyzerEvidence(
            featureVersion = "features-1",
            featureDigest = "features-hash",
            hasValidNativeText = native,
            nativeTextComplete = complete,
            visualTextLikely = !native,
        ),
        artifacts = artifacts,
        capabilities = capabilities,
        policy = policy,
        inputDigest = "input-hash",
        historicalEstimates = estimates,
    )

    @Test
    fun `valid native text chooses text only`() {
        val result = ExecutionPlanner().plan(input(native = true)) as PlannerResult.Planned
        assertEquals(PlanFamily.TEXT_ONLY, result.plan.family)
        assertEquals(SemanticCoverage.NOT_REQUIRED, result.plan.semanticCoverage)
        assertTrue(result.plan.nodes.none { it.id == OperatorId.PRELIGHT_IMAGE })
    }

    @Test
    fun `exact complete artifact chooses reuse`() {
        val result = ExecutionPlanner().plan(
            input(artifacts = ArtifactState(
                manifestGeneration = 4,
                exactReadyModes = setOf(RetrievalMode.LEXICAL),
                operatorVersion = "op-1",
                certificateValid = true,
            )),
        ) as PlannerResult.Planned
        assertEquals(PlanFamily.REUSE, result.plan.family)
        assertEquals(listOf(OperatorId.PUBLISH_REUSE), result.plan.nodes.map { it.id })
    }

    @Test
    fun `image chooses cascade and declares conditional escalation`() {
        val result = ExecutionPlanner().plan(input(sourceType = SourceType.IMAGE)) as PlannerResult.Planned
        assertEquals(PlanFamily.OCR_CASCADE, result.plan.family)
        assertTrue(result.plan.nodes.single { it.id == OperatorId.OCR_ESCALATE }.conditional)
    }

    @Test
    fun `semantic request can choose deferred lexical plan when complete plan misses budget`() {
        val strictBudget = PlannerPolicy(
            requiredModes = setOf(RetrievalMode.SEMANTIC),
            allowDeferredSemantic = true,
            budget = ResourceBudget(maxLatencyMs = 500),
        )
        val result = ExecutionPlanner().plan(
            input(
                native = true,
                required = setOf(RetrievalMode.SEMANTIC),
                policy = strictBudget,
            ),
        )
            as PlannerResult.Planned
        assertEquals(PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED, result.plan.family)
        assertEquals(SemanticCoverage.DEFERRED, result.plan.semanticCoverage)
    }

    @Test
    fun `quality and memory constraints reject unsafe candidates`() {
        val expensive = CostEstimate(
            latencyP50Ms = 100,
            latencyP95Ms = 100,
            energyProxy = 1.0,
            peakBytesP95 = 900_000_000,
            writeBytes = 1,
            coldStartRisk = 0.0,
            failureRisk = 0.0,
            qualityLowerBound = 0.999,
        )
        val result = ExecutionPlanner().plan(input(estimates = mapOf(PlanFamily.OCR_CASCADE to expensive)))
        assertTrue(result is PlannerResult.Refused)
        val decision = (result as PlannerResult.Refused).decision
        assertTrue(decision.candidates.any { it.rejectedBecause == "DEVICE_MEMORY_RESERVATION" })
    }

    @Test
    fun `malformed source is refused before any worker plan is emitted`() {
        val result = ExecutionPlanner().plan(
            input(policy = PlannerPolicy(), estimates = emptyMap()).copy(
                evidence = AnalyzerEvidence(
                    featureVersion = "features-1",
                    featureDigest = "features-hash",
                    isMalformed = true,
                ),
            ),
        )
        assertTrue(result is PlannerResult.Refused)
        assertEquals("INPUT_INVALID", (result as PlannerResult.Refused).refusal.code)
    }

    @Test
    fun `deferred semantic plan is not offered for image without native text`() {
        val result = ExecutionPlanner().plan(
            input(
                sourceType = SourceType.IMAGE,
                required = setOf(RetrievalMode.SEMANTIC),
                policy = PlannerPolicy(requiredModes = setOf(RetrievalMode.SEMANTIC)),
            ),
        ) as PlannerResult.Planned
        assertEquals(PlanFamily.OCR_CASCADE_SEMANTIC, result.plan.family)
    }

    @Test
    fun `decision and plan are deterministic`() {
        val planner = ExecutionPlanner()
        val first = planner.plan(input(native = true)) as PlannerResult.Planned
        val second = planner.plan(input(native = true)) as PlannerResult.Planned
        assertEquals(first.plan, second.plan)
        assertEquals(first.decision, second.decision)
    }

    @Test
    fun `tie breaks by stable family name`() {
        val same = CostEstimate(100, 100, 1.0, 1_000_000, 1, 0.0, 0.0, 1.0)
        val policy = PlannerPolicy(
            requiredModes = setOf(RetrievalMode.SEMANTIC),
            allowDeferredSemantic = true,
        )
        val result = ExecutionPlanner().plan(
            input(
                sourceType = SourceType.PDF,
                native = true,
                required = setOf(RetrievalMode.SEMANTIC),
                estimates = mapOf(
                    PlanFamily.TEXT_SEMANTIC to same,
                    PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED to same,
                ),
                policy = policy,
            ),
        ) as PlannerResult.Planned
        assertEquals(PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED, result.plan.family)
    }
}
