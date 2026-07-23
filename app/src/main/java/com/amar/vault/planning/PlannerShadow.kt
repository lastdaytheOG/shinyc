package com.amar.vault.planning

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/** A privacy-safe, bounded observation emitted by shadow planning. */
data class PlannerShadowRecord(
    val sourceClass: SourceType,
    val phase: String,
    val sourceFingerprint: String,
    val plan: ExecutionPlan?,
    val decision: PlannerDecision,
    /** Legacy operators observed for this entry point; this is a work-count proxy, not timing. */
    val observedOperators: Set<String> = emptySet(),
)

fun interface PlannerDecisionSink {
    fun record(value: PlannerShadowRecord)
}

interface PlannerDecisionSnapshotSource {
    fun snapshot(): List<PlannerShadowRecord>
}

/** Thread-safe bounded sink used by developer tooling and JVM tests. */
class InMemoryPlannerDecisionSink(private val capacity: Int = 512) :
    PlannerDecisionSink, PlannerDecisionSnapshotSource {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val values = ArrayDeque<PlannerShadowRecord>(capacity)

    @Synchronized
    override fun record(value: PlannerShadowRecord) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(value)
    }

    @Synchronized
    override fun snapshot(): List<PlannerShadowRecord> = values.toList()

    @Synchronized
    fun clear() {
        values.clear()
    }
}

/**
 * Global switch used only for shadow capture. Disabled by default, and the fast path returns
 * before constructing any planner input. No Worker consults this registry yet.
 */
object PlannerShadowRegistry {
    private val noOpSink = PlannerDecisionSink { }
    private val defaultSink = InMemoryPlannerDecisionSink()
    private val sinkRef = AtomicReference<PlannerDecisionSink>(noOpSink)
    @Volatile private var enabled = false
    private val adapter = PlannerShadowAdapter()

    fun enable(sink: PlannerDecisionSink) {
        sinkRef.set(sink)
        enabled = true
    }

    fun disable() {
        enabled = false
        sinkRef.set(noOpSink)
    }

    /** Uses the bounded built-in capture buffer; this is the mode tied to developer capture. */
    fun setDefaultCaptureEnabled(value: Boolean) {
        if (value) {
            sinkRef.set(defaultSink)
            enabled = true
        } else {
            disable()
        }
    }

    fun isEnabled(): Boolean = enabled

    /** Returns records only when the configured sink supports replay; otherwise an honest empty list. */
    fun snapshot(): List<PlannerShadowRecord> =
        (sinkRef.get() as? PlannerDecisionSnapshotSource)?.snapshot().orEmpty()

    fun clear() {
        defaultSink.clear()
        (sinkRef.get() as? InMemoryPlannerDecisionSink)?.clear()
    }

    fun observeImage(sourceId: String, itemType: String, width: Int, height: Int) {
        if (!enabled) return
        sinkRef.get().record(adapter.observeImage(sourceId, itemType, width, height))
    }

    fun observeDocument(
        sourceId: String,
        mimeType: String,
        pageCount: Int,
        nonEmptyPages: Int,
    ) {
        if (!enabled) return
        sinkRef.get().record(adapter.observeDocument(sourceId, mimeType, pageCount, nonEmptyPages))
    }
}

/**
 * Converts legacy entry-point observations into PlannerInput. This is deliberately post-hoc for
 * documents: the legacy extractor remains the oracle while the planner learns from its evidence.
 */
class PlannerShadowAdapter(
    private val planner: ExecutionPlanner = ExecutionPlanner(),
    private val capabilities: () -> CapabilitySnapshot = ::defaultCapabilities,
) {
    fun observeImage(sourceId: String, itemType: String, width: Int, height: Int): PlannerShadowRecord {
        val fingerprint = digest("image|$sourceId|$width|$height|$itemType")
        val input = PlannerInput(
            source = SourceIdentity(sourceId, fingerprint, SourceType.IMAGE, 0L),
            evidence = AnalyzerEvidence(
                featureVersion = "shadow-image-1",
                featureDigest = digest("$width|$height"),
                pageCount = 1,
                visualTextLikely = true,
                estimatedPixels = width.toLong().coerceAtLeast(0) * height.coerceAtLeast(0),
            ),
            capabilities = capabilities(),
            inputDigest = digest("$fingerprint|image"),
        )
        return toRecord("IMAGE_PRE_OCR", input, legacyImageOperators())
    }

    fun observeDocument(
        sourceId: String,
        mimeType: String,
        pageCount: Int,
        nonEmptyPages: Int,
    ): PlannerShadowRecord {
        val sourceType = sourceTypeForMime(mimeType)
        val safePages = pageCount.coerceAtLeast(1)
        val fingerprint = digest("document|$sourceId|$mimeType|$safePages|$nonEmptyPages")
        val nativeComplete = nonEmptyPages >= safePages
        val input = PlannerInput(
            source = SourceIdentity(sourceId, fingerprint, sourceType, 0L),
            evidence = AnalyzerEvidence(
                featureVersion = "shadow-document-1",
                featureDigest = digest("$mimeType|$safePages|$nonEmptyPages"),
                pageCount = safePages,
                hasValidNativeText = nonEmptyPages > 0,
                nativeTextComplete = nativeComplete,
                nativeTextConfidence = if (nativeComplete) 1.0 else 0.5,
                visualTextLikely = sourceType == SourceType.PDF,
                estimatedTextPages = nonEmptyPages.coerceIn(0, safePages),
            ),
            capabilities = capabilities(),
            inputDigest = digest("$fingerprint|document"),
        )
        return toRecord("POST_LEGACY_EXTRACTION", input, legacyDocumentOperators())
    }

    private fun toRecord(
        phase: String,
        input: PlannerInput,
        observedOperators: Set<String>,
    ): PlannerShadowRecord {
        val result = planner.plan(input)
        return when (result) {
            is PlannerResult.Planned -> PlannerShadowRecord(
                sourceClass = input.source.sourceType,
                phase = phase,
                sourceFingerprint = input.source.contentFingerprint,
                plan = result.plan,
                decision = result.decision,
                observedOperators = observedOperators,
            )
            is PlannerResult.Refused -> PlannerShadowRecord(
                sourceClass = input.source.sourceType,
                phase = phase,
                sourceFingerprint = input.source.contentFingerprint,
                plan = null,
                decision = result.decision,
                observedOperators = observedOperators,
            )
        }
    }

    private fun legacyImageOperators(): Set<String> = setOf(
        "legacy.bitmap.decode",
        "legacy.ocr.raw",
        "legacy.ocr.grayscale",
        "legacy.ocr.invert",
        "legacy.ocr.upscale",
        "legacy.ocr.tesseract",
        "legacy.barcode.scan",
        "legacy.metadata.extract",
        "legacy.commit",
        "legacy.chunk",
        "legacy.embedding.chunk",
    )

    private fun legacyDocumentOperators(): Set<String> = setOf(
        "legacy.document.parse",
        "legacy.document.dedup",
        "legacy.chunk",
        "legacy.bm25",
        "legacy.commit",
    )

    private fun sourceTypeForMime(mimeType: String): SourceType = when {
        mimeType.equals("application/pdf", ignoreCase = true) -> SourceType.PDF
        mimeType.startsWith("text/", ignoreCase = true) -> SourceType.TEXT
        mimeType.startsWith("image/", ignoreCase = true) -> SourceType.IMAGE
        mimeType.contains("word", ignoreCase = true) || mimeType.contains("sheet", ignoreCase = true) ||
            mimeType.contains("document", ignoreCase = true) -> SourceType.OFFICE
        mimeType.contains("zip", ignoreCase = true) || mimeType.contains("archive", ignoreCase = true) ->
            SourceType.ARCHIVE
        else -> SourceType.UNKNOWN
    }

    companion object {
        private fun defaultCapabilities(): CapabilitySnapshot = CapabilitySnapshot(
            capabilityEpoch = 0L,
            nativeFreeBytes = Long.MAX_VALUE / 2,
            cpuTokens = 1,
            heavyOcrSlots = 1,
            embeddingSlots = 1,
        )

        private fun digest(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Stable, payload-free line format for replay/debug export. */
object PlannerDecisionLogEncoder {
    fun encode(record: PlannerShadowRecord): String {
        val d = record.decision
        val selected = d.selectedFamily?.name ?: "REFUSED"
        val candidates = d.candidates.joinToString(",") {
            "${it.family.name}:${if (it.feasible) "ok" else "reject"}:${it.rejectedBecause ?: ""}"
        }
        return listOf(
            "schema=1",
            "phase=${record.phase}",
            "sourceClass=${record.sourceClass.name}",
            "sourceFingerprint=${record.sourceFingerprint}",
            "decisionId=${d.decisionId}",
            "inputDigest=${d.inputDigest}",
            "plannerVersion=${d.plannerVersion}",
            "policyVersion=${d.policyVersion}",
            "costModelVersion=${d.costModelVersion}",
            "capabilityEpoch=${d.capabilityEpoch}",
            "selected=$selected",
            "deterministic=${d.deterministic}",
            "seed=${d.seed}",
            "candidates=$candidates",
        ).joinToString(" ")
    }
}
