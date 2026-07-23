package com.amar.vault.planning

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerShadowTest {
    @After
    fun tearDown() {
        PlannerShadowRegistry.disable()
    }

    @Test
    fun `disabled shadow registry performs no observation`() {
        val sink = InMemoryPlannerDecisionSink()
        PlannerShadowRegistry.disable()
        PlannerShadowRegistry.enable(sink)
        PlannerShadowRegistry.disable()
        PlannerShadowRegistry.observeImage("private-uri", "screenshot", 100, 100)
        assertTrue(sink.snapshot().isEmpty())
        assertFalse(PlannerShadowRegistry.isEnabled())
    }

    @Test
    fun `default capture retains observations for the benchmark session`() {
        PlannerShadowRegistry.setDefaultCaptureEnabled(true)
        PlannerShadowRegistry.observeImage("private-uri", "screenshot", 100, 100)
        assertEquals(1, PlannerShadowRegistry.snapshot().size)
        PlannerShadowRegistry.clear()
        assertTrue(PlannerShadowRegistry.snapshot().isEmpty())
    }

    @Test
    fun `enabled image observation emits deterministic planner decision`() {
        val sink = InMemoryPlannerDecisionSink(2)
        PlannerShadowRegistry.enable(sink)
        PlannerShadowRegistry.observeImage("private-uri", "screenshot", 1000, 800)
        PlannerShadowRegistry.observeImage("private-uri", "screenshot", 1000, 800)
        val records = sink.snapshot()
        assertEquals(2, records.size)
        assertEquals(records[0], records[1])
        assertEquals(PlanFamily.OCR_CASCADE, records[0].plan?.family)
        assertTrue(records[0].decision.deterministic)
        assertTrue(records[0].observedOperators.any { it == "legacy.ocr.tesseract" })
    }

    @Test
    fun `sink is bounded and log excludes source payload`() {
        val sink = InMemoryPlannerDecisionSink(1)
        val adapter = PlannerShadowAdapter()
        sink.record(adapter.observeDocument("secret-uri", "application/pdf", 2, 2))
        sink.record(adapter.observeDocument("second-uri", "application/pdf", 2, 1))
        val records = sink.snapshot()
        assertEquals(1, records.size)
        val log = PlannerDecisionLogEncoder.encode(records.single())
        assertTrue(log.contains("sourceClass=PDF"))
        assertFalse(log.contains("secret-uri"))
        assertFalse(log.contains("second-uri"))
        assertFalse(log.contains("recognized-text"))
    }

    @Test
    fun `post extraction complete text selects text only`() {
        val record = PlannerShadowAdapter().observeDocument("doc", "application/pdf", 3, 3)
        assertEquals(PlanFamily.TEXT_ONLY, record.plan?.family)
        assertEquals("POST_LEGACY_EXTRACTION", record.phase)
    }

    @Test
    fun `shadow benchmark is honest when no observations exist`() {
        val section = com.amar.vault.benchmark.PlannerShadowBenchmark.section(emptyList())
        assertEquals("planner.shadow", section.id)
        assertEquals(null, section.metrics.first {
            it.name == "shadow.operatorCountReduction.pct"
        }.value)
        assertTrue(section.metrics.first { it.name == "shadow.decisions" }.note.contains("no shadow"))
    }

    @Test
    fun `readiness audit blocks worker rollout without quality evidence`() {
        val section = com.amar.vault.benchmark.PlannerReadinessAudit.section(
            dataset = com.amar.vault.benchmark.GoldenDatasetAudit.Result(
                scorableRetrievalCases = 0,
                scorableOcrCases = 0,
                rows = emptyList(),
            ),
            shadowRecords = emptyList(),
            baselineRunId = null,
        )
        assertEquals(0.0, section.metrics.first { it.name == "readiness.workerRollout" }.value)
        assertEquals(4, section.rows.size)
    }

    @Test
    fun `readiness uses runnable evidence rather than declared schema cases`() {
        val section = com.amar.vault.benchmark.PlannerReadinessAudit.section(
            dataset = com.amar.vault.benchmark.GoldenDatasetAudit.Result(
                scorableRetrievalCases = 1,
                scorableOcrCases = 1,
                rows = listOf(mapOf("caseId" to "valid-case", "status" to "READY")),
            ),
            shadowRecords = listOf(PlannerShadowAdapter().observeDocument("doc", "application/pdf", 1, 1)),
            baselineRunId = "before-change-full",
        )
        assertEquals(1.0, section.metrics.first { it.name == "readiness.workerRollout" }.value)
        assertEquals(0.0, section.metrics.first { it.name == "readiness.blockers" }.value)
        assertTrue(section.rows.any { it["status"] == "READY" })
    }
}
