package com.amar.vault.planning

/**
 * Deterministic candidate planner. It only chooses work; Workers own execution and measurement.
 */
class ExecutionPlanner(
    private val costModel: PlannerCostModel = DefaultPlannerCostModel(),
) {
    fun plan(input: PlannerInput): PlannerResult {
        require(input.policy.memorySafetyFraction in 0.0..1.0) {
            "memorySafetyFraction must be between 0 and 1"
        }
        val digest = input.inputDigest.ifBlank {
            "${input.source.contentFingerprint}:${input.evidence.featureDigest}"
        }
        val seed = stableSeed(digest)
        val candidates = candidateFamilies(input)
            .distinct()
            .sortedBy { it.name }
            .map { family -> candidateDecision(family, input) }

        val feasible = candidates.filter { it.feasible }
        val chosen = feasible.minWithOrNull(
            compareBy<CandidateDecision> { it.score ?: Double.POSITIVE_INFINITY }
                .thenBy { it.family.name },
        )
        val decision = PlannerDecision(
            decisionId = "$digest:${input.policy.plannerVersion}",
            inputDigest = digest,
            plannerVersion = input.policy.plannerVersion,
            policyVersion = input.policy.policyVersion,
            costModelVersion = costModel.version,
            capabilityEpoch = input.capabilities.capabilityEpoch,
            candidates = candidates,
            selectedFamily = chosen?.family,
            decisionLatencyMs = 0L,
            deterministic = true,
            seed = seed,
        )

        if (chosen == null) {
            return PlannerResult.Refused(
                PlannerRefusal(
                    code = if (input.evidence.isMalformed) "INPUT_INVALID" else "NO_FEASIBLE_PLAN",
                    message = if (input.evidence.isMalformed) {
                        "Analyzer marked source malformed"
                    } else {
                        "All candidate plans violate quality, resource, capability, or deadline constraints"
                    },
                    retryable = !input.evidence.isMalformed,
                    safePartialFamily = PlanFamily.SAFE_QUARANTINE.takeIf { !input.evidence.isMalformed },
                ),
                decision,
            )
        }

        val plan = buildPlan(chosen.family, input, digest, seed, chosen.estimate)
        return PlannerResult.Planned(plan, decision)
    }

    private fun candidateFamilies(input: PlannerInput): List<PlanFamily> {
        if (input.evidence.isMalformed) return emptyList()
        val required = input.policy.requiredModes
        val semantic = RetrievalMode.SEMANTIC in required || RetrievalMode.HYBRID in required
        if (input.artifacts.certificateValid &&
            input.artifacts.exactReadyModes.containsAll(required)
        ) return listOf(PlanFamily.REUSE)

        val textCapable = input.evidence.hasValidNativeText && input.evidence.nativeTextComplete
        val candidates = mutableListOf<PlanFamily>()
        if (textCapable) {
            candidates += if (semantic) PlanFamily.TEXT_SEMANTIC else PlanFamily.TEXT_ONLY
        } else if (input.source.sourceType == SourceType.TEXT || input.source.sourceType == SourceType.OFFICE) {
            candidates += PlanFamily.TEXT_ONLY
        } else if (input.source.sourceType == SourceType.PDF || input.evidence.visualTextLikely) {
            candidates += if (semantic) PlanFamily.OCR_CASCADE_SEMANTIC else PlanFamily.OCR_CASCADE
        }
        val lexicalCanBeImmediate = textCapable ||
            input.source.sourceType == SourceType.TEXT || input.source.sourceType == SourceType.OFFICE
        if (semantic && lexicalCanBeImmediate && input.policy.allowDeferredSemantic &&
            !input.policy.requireSemanticCompleteBeforePublish
        ) {
            candidates += PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED
        }
        if (candidates.isEmpty()) candidates += PlanFamily.SAFE_QUARANTINE
        return candidates
    }

    private fun candidateDecision(family: PlanFamily, input: PlannerInput): CandidateDecision {
        val estimate = costModel.estimate(family, input)
        val unavailable = requiredOperators(family).firstOrNull {
            it !in input.capabilities.availableOperators
        }
        val reason = when {
            unavailable != null -> "CAPABILITY_MISSING:${unavailable.name}"
            !estimate.feasible(input.policy, input.capabilities) -> infeasibilityReason(estimate, input)
            else -> null
        }
        return CandidateDecision(
            family = family,
            feasible = reason == null,
            score = if (reason == null) estimate.score(input.policy) else null,
            estimate = estimate,
            rejectedBecause = reason,
        )
    }

    private fun buildPlan(
        family: PlanFamily,
        input: PlannerInput,
        digest: String,
        seed: Long,
        estimate: CostEstimate,
    ): ExecutionPlan {
        val nodes = when (family) {
            PlanFamily.REUSE -> listOf(OperatorNode(OperatorId.PUBLISH_REUSE, WorkerClass.MANIFEST))
            PlanFamily.TEXT_ONLY -> listOf(
                OperatorNode(OperatorId.READ_NATIVE_TEXT, WorkerClass.PARSER),
                OperatorNode(OperatorId.TOKENIZE_AND_POSTINGS, WorkerClass.TOKENIZER,
                    listOf(OperatorId.READ_NATIVE_TEXT)),
                OperatorNode(OperatorId.COMMIT_MANIFEST, WorkerClass.COMMIT,
                    listOf(OperatorId.TOKENIZE_AND_POSTINGS)),
            )
            PlanFamily.TEXT_SEMANTIC -> listOf(
                OperatorNode(OperatorId.READ_NATIVE_TEXT, WorkerClass.PARSER),
                OperatorNode(OperatorId.TOKENIZE_AND_POSTINGS, WorkerClass.TOKENIZER,
                    listOf(OperatorId.READ_NATIVE_TEXT)),
                OperatorNode(OperatorId.EMBED_DOCUMENT, WorkerClass.EMBEDDING,
                    listOf(OperatorId.READ_NATIVE_TEXT)),
                OperatorNode(OperatorId.COMMIT_MANIFEST, WorkerClass.COMMIT,
                    listOf(OperatorId.TOKENIZE_AND_POSTINGS, OperatorId.EMBED_DOCUMENT)),
            )
            PlanFamily.OCR_CASCADE, PlanFamily.OCR_CASCADE_SEMANTIC -> {
                val nodes = mutableListOf(
                    OperatorNode(OperatorId.PRELIGHT_IMAGE, WorkerClass.RASTER),
                    OperatorNode(OperatorId.OCR_LIGHT, WorkerClass.OCR_LIGHT,
                        listOf(OperatorId.PRELIGHT_IMAGE)),
                    OperatorNode(OperatorId.OCR_ESCALATE, WorkerClass.OCR_HEAVY,
                        listOf(OperatorId.OCR_LIGHT), conditional = true),
                    OperatorNode(OperatorId.TOKENIZE_AND_POSTINGS, WorkerClass.TOKENIZER,
                        listOf(OperatorId.OCR_LIGHT, OperatorId.OCR_ESCALATE)),
                )
                if (family == PlanFamily.OCR_CASCADE_SEMANTIC) {
                    nodes += OperatorNode(OperatorId.EMBED_DOCUMENT, WorkerClass.EMBEDDING,
                        listOf(OperatorId.TOKENIZE_AND_POSTINGS))
                }
                nodes += OperatorNode(
                    OperatorId.COMMIT_MANIFEST,
                    WorkerClass.COMMIT,
                    nodes.map { it.id },
                )
                nodes
            }
            PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED -> listOf(
                OperatorNode(OperatorId.READ_NATIVE_TEXT, WorkerClass.PARSER),
                OperatorNode(OperatorId.TOKENIZE_AND_POSTINGS, WorkerClass.TOKENIZER,
                    listOf(OperatorId.READ_NATIVE_TEXT)),
                OperatorNode(OperatorId.ENQUEUE_SEMANTIC, WorkerClass.EMBEDDING,
                    listOf(OperatorId.TOKENIZE_AND_POSTINGS)),
                OperatorNode(OperatorId.COMMIT_MANIFEST, WorkerClass.COMMIT,
                    listOf(OperatorId.TOKENIZE_AND_POSTINGS, OperatorId.ENQUEUE_SEMANTIC)),
            )
            PlanFamily.SAFE_QUARANTINE -> listOf(OperatorNode(OperatorId.COMMIT_MANIFEST, WorkerClass.COMMIT))
        }
        val fallback = when (family) {
            PlanFamily.REUSE -> listOf(FallbackRule(FallbackTrigger.ARTIFACT_CONFLICT, PlanFamily.TEXT_ONLY))
            PlanFamily.TEXT_ONLY -> listOf(
                FallbackRule(FallbackTrigger.QUALITY_PREDICATE_FAILED, PlanFamily.OCR_CASCADE),
                FallbackRule(FallbackTrigger.WORKER_ERROR, PlanFamily.SAFE_QUARANTINE),
            )
            PlanFamily.TEXT_SEMANTIC -> listOf(
                FallbackRule(FallbackTrigger.QUALITY_PREDICATE_FAILED, PlanFamily.OCR_CASCADE_SEMANTIC),
                FallbackRule(FallbackTrigger.WORKER_ERROR, PlanFamily.SAFE_QUARANTINE),
            )
            PlanFamily.OCR_CASCADE, PlanFamily.OCR_CASCADE_SEMANTIC -> listOf(
                FallbackRule(FallbackTrigger.TIMEOUT, PlanFamily.SAFE_QUARANTINE),
                FallbackRule(FallbackTrigger.RESOURCE_DENIED, PlanFamily.SAFE_QUARANTINE),
            )
            PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED -> listOf(
                FallbackRule(FallbackTrigger.WORKER_ERROR, PlanFamily.SAFE_QUARANTINE),
            )
            PlanFamily.SAFE_QUARANTINE -> emptyList()
        }
        val semantic = when (family) {
            PlanFamily.TEXT_SEMANTIC, PlanFamily.OCR_CASCADE_SEMANTIC -> SemanticCoverage.COMPLETE
            PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED -> SemanticCoverage.DEFERRED
            else -> if (input.policy.requiredModes.any { it == RetrievalMode.SEMANTIC || it == RetrievalMode.HYBRID }) {
                SemanticCoverage.DEFERRED
            } else SemanticCoverage.NOT_REQUIRED
        }
        return ExecutionPlan(
            planId = "$digest:${family.name}:${input.policy.plannerVersion}",
            family = family,
            nodes = nodes,
            fallbackChain = fallback,
            estimate = estimate,
            semanticCoverage = semantic,
            qualityContract = input.policy.minimumQualityLowerBound,
            inputDigest = digest,
            determinismSeed = seed,
        )
    }

    private fun requiredOperators(family: PlanFamily): Set<OperatorId> = when (family) {
        PlanFamily.REUSE -> setOf(OperatorId.PUBLISH_REUSE)
        PlanFamily.TEXT_ONLY, PlanFamily.TEXT_SEMANTIC ->
            setOf(
                OperatorId.READ_NATIVE_TEXT,
                OperatorId.TOKENIZE_AND_POSTINGS,
                OperatorId.COMMIT_MANIFEST,
            ) + if (family == PlanFamily.TEXT_SEMANTIC) {
                setOf(OperatorId.EMBED_DOCUMENT)
            } else emptySet()
        PlanFamily.OCR_CASCADE, PlanFamily.OCR_CASCADE_SEMANTIC ->
            setOf(OperatorId.PRELIGHT_IMAGE, OperatorId.OCR_LIGHT, OperatorId.TOKENIZE_AND_POSTINGS,
                OperatorId.COMMIT_MANIFEST, OperatorId.OCR_ESCALATE) +
                if (family == PlanFamily.OCR_CASCADE_SEMANTIC) {
                    setOf(OperatorId.EMBED_DOCUMENT)
                } else emptySet()
        PlanFamily.LEXICAL_NOW_SEMANTIC_DEFERRED ->
            setOf(OperatorId.READ_NATIVE_TEXT, OperatorId.TOKENIZE_AND_POSTINGS,
                OperatorId.ENQUEUE_SEMANTIC, OperatorId.COMMIT_MANIFEST)
        PlanFamily.SAFE_QUARANTINE -> setOf(OperatorId.COMMIT_MANIFEST)
    }

    private fun infeasibilityReason(estimate: CostEstimate, input: PlannerInput): String = when {
        estimate.qualityLowerBound < input.policy.minimumQualityLowerBound -> "QUALITY_BELOW_FLOOR"
        estimate.latencyP95Ms > input.policy.budget.maxLatencyMs -> "LATENCY_BUDGET"
        estimate.peakBytesP95 > input.policy.budget.maxPeakBytes -> "PLAN_MEMORY_BUDGET"
        estimate.peakBytesP95 > input.capabilities.nativeFreeBytes *
            (1.0 - input.policy.memorySafetyFraction) -> "DEVICE_MEMORY_RESERVATION"
        estimate.writeBytes > input.policy.budget.maxWriteBytes -> "WRITE_BUDGET"
        estimate.escalations > input.policy.budget.maxEscalations -> "ESCALATION_BUDGET"
        else -> "UNKNOWN_INFEASIBILITY"
    }

    private fun stableSeed(value: String): Long = value.fold(1125899906842597L) { hash, char ->
        hash * 31 + char.code
    }
}
