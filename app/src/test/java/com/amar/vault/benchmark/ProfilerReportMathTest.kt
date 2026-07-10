package com.amar.vault.benchmark

import com.amar.vault.indexing.DocProfile
import com.amar.vault.indexing.DocProfileRecorder
import com.amar.vault.indexing.PageProfile
import com.amar.vault.indexing.ProfilerStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint E3 — pure-JVM tests for the profiler report math and the recorder's derived values.
 * No Android, no native libs: these must run green on any host.
 */
class ProfilerReportMathTest {

    private fun page(
        n: Int,
        stripShare: Double = 0.0,
        nfc: Double = 0.0,
        trust: Double = 0.0,
        score: Double? = null,
        trusted: Boolean? = null,
        render: Double = 0.0,
        hiRes: Double = 0.0,
        ocr: Double = 0.0,
        ocrUsed: Boolean = ocr > 0,
        bmpW: Int = 0,
        bmpH: Int = 0,
    ) = PageProfile(
        page = n, stripShareMs = stripShare, nfcMs = nfc, trustMs = trust,
        trustScore = score, trusted = trusted, ocrUsed = ocrUsed, ocrTier1Accepted = null,
        renderMs = render, hiResRenderMs = hiRes, ocrMs = ocr,
        bitmapWidth = bmpW, bitmapHeight = bmpH, bitmapBytes = 0L,
        pageTotalMs = stripShare + nfc + trust + render + hiRes + ocr,
    )

    private fun doc(
        name: String,
        totalMs: Double,
        stages: Map<String, Double> = emptyMap(),
        pages: List<PageProfile> = emptyList(),
        pageCount: Int = pages.size,
    ) = DocProfile(
        docId = name, displayName = name, fileType = "pdf", origin = "test", status = "success",
        totalMs = totalMs, stagesMs = stages, pageCount = pageCount,
        trustedPages = pages.count { it.trusted == true },
        ocrFallbackPages = pages.count { it.trusted == false },
        avgTrustScore = pages.mapNotNull { it.trustScore }.takeIf { it.isNotEmpty() }?.average(),
        largestBitmapWidth = 0, largestBitmapHeight = 0, largestBitmapBytes = 0L,
        pages = pages,
    )

    // ── Percentiles ───────────────────────────────────────────────────────────

    @Test
    fun `percentiles of empty list is null`() {
        assertNull(ProfilerReportMath.percentiles(emptyList()))
    }

    @Test
    fun `percentiles use the same index method as BenchmarkMath`() {
        val values = (1..100).map { it.toDouble() }.shuffled()
        val p = ProfilerReportMath.percentiles(values)!!
        assertEquals(100, p.n)
        assertEquals(50.0, p.p50, 1e-9)   // idx (99*0.50)=49 → value 50
        assertEquals(90.0, p.p90, 1e-9)
        assertEquals(95.0, p.p95, 1e-9)
        assertEquals(99.0, p.p99, 1e-9)
        assertEquals(100.0, p.max, 1e-9)
    }

    // ── Rankings ──────────────────────────────────────────────────────────────

    @Test
    fun `topDocuments sorts by total descending and truncates`() {
        val docs = listOf(doc("a", 100.0), doc("b", 5000.0), doc("c", 700.0))
        val top = ProfilerReportMath.topDocuments(docs, 2)
        assertEquals(listOf("b", "c"), top.map { it.displayName })
    }

    @Test
    fun `topPages sorts across documents by page total`() {
        val d1 = doc("d1", 100.0, pages = listOf(page(1, ocr = 800.0), page(2, ocr = 100.0)))
        val d2 = doc("d2", 100.0, pages = listOf(page(7, ocr = 6000.0)))
        val top = ProfilerReportMath.topPages(listOf(d1, d2), 2)
        assertEquals(listOf("d2" to 7, "d1" to 1), top.map { it.doc.displayName to it.page.page })
    }

    // ── Outliers ──────────────────────────────────────────────────────────────

    @Test
    fun `all five outlier kinds fire at their thresholds`() {
        val bad = doc(
            "bad", totalMs = 31_000.0,
            pages = listOf(
                page(1, ocr = 3500.0),                        // ocr>3000 (+ its render is 0)
                page(2, stripShare = 400.0),                  // stripShare>300
                page(3, render = 400.0, hiRes = 200.0),       // render sum 600 > 500
                page(4, ocr = 2000.0, render = 100.0, stripShare = 100.0, nfc = 3000.0), // total 5200 > 5000
            ),
        )
        val kinds = ProfilerReportMath.outliers(listOf(bad)).map { it.kind }
        assertTrue("docTotal missing: $kinds", kinds.contains("docTotal>30s"))
        assertTrue(kinds.contains("ocr>3000ms"))
        assertTrue(kinds.contains("stripShare>300ms"))
        assertTrue(kinds.contains("render>500ms"))
        assertTrue(kinds.contains("pageTotal>5000ms"))
    }

    @Test
    fun `no outliers below thresholds and sorted by value descending`() {
        val ok = doc("ok", 29_000.0, pages = listOf(page(1, ocr = 2999.0, render = 500.0)))
        assertTrue(ProfilerReportMath.outliers(listOf(ok)).isEmpty())

        val bad = doc("bad", 40_000.0, pages = listOf(page(1, ocr = 3500.0)))
        val outs = ProfilerReportMath.outliers(listOf(bad))
        assertEquals(listOf(40_000.0, 3500.0), outs.map { it.valueMs })
    }

    // ── Stage decomposition ───────────────────────────────────────────────────

    @Test
    fun `stageTotals replaces extract with extractOther to avoid double counting`() {
        val d = doc(
            "d", 2000.0,
            stages = mapOf(
                ProfilerStage.EXTRACT to 1000.0,
                ProfilerStage.PDF_STRIP to 300.0,
                ProfilerStage.RENDER to 200.0,
                ProfilerStage.PAGE_OCR to 400.0,
                ProfilerStage.ROOM to 50.0,
                ProfilerStage.BM25 to 30.0,
            ),
        )
        val totals = ProfilerReportMath.stageTotals(listOf(d))
        assertEquals(100.0, totals[ProfilerReportMath.STAGE_EXTRACT_OTHER]!!, 1e-9)
        assertEquals(300.0, totals[ProfilerStage.PDF_STRIP]!!, 1e-9)
        assertNull(totals[ProfilerStage.EXTRACT]) // never reported raw
        // Sum is disjoint: 300+200+400+100+50+30
        assertEquals(1080.0, totals.values.sum(), 1e-9)
    }

    @Test
    fun `stageTotals clamps extractOther at zero when overlapped stages exceed extract`() {
        // PDF pipeline overlap: page OCR (600) overlaps strip, so nested sums (900) can
        // exceed the extract wall time (700). extractOther must clamp, not go negative.
        val d = doc("d", 800.0, stages = mapOf(
            ProfilerStage.EXTRACT to 700.0,
            ProfilerStage.PDF_STRIP to 300.0,
            ProfilerStage.PAGE_OCR to 600.0,
        ))
        val totals = ProfilerReportMath.stageTotals(listOf(d))
        assertNull(totals[ProfilerReportMath.STAGE_EXTRACT_OTHER]) // 0 → not reported
        assertEquals(900.0, totals.values.sum(), 1e-9)
    }

    // ── Concentration ─────────────────────────────────────────────────────────

    @Test
    fun `concentration detects a pathological outlier`() {
        val values = List(19) { 10.0 } + 810.0 // top 1 of 20 holds 81%
        val c = ProfilerReportMath.concentration(values)!!
        assertEquals(1, c.topCount)
        assertEquals(20, c.outOf)
        assertEquals(81.0, c.topSharePct, 1e-9)
    }

    @Test
    fun `concentration of uniform values is proportional`() {
        val c = ProfilerReportMath.concentration(List(100) { 5.0 })!!
        assertEquals(5, c.topCount)
        assertEquals(5.0, c.topSharePct, 1e-9)
        assertNull(ProfilerReportMath.concentration(emptyList()))
    }

    // ── Diagnosis ─────────────────────────────────────────────────────────────

    @Test
    fun `diagnosis with no data says so instead of fabricating`() {
        val d = ProfilerReportMath.diagnosis(emptyList())
        assertEquals(1, d.size)
        assertTrue(d[0].answer.contains("no measured data"))
    }

    @Test
    fun `diagnosis answers all five questions with measured numbers`() {
        val big = doc(
            "report.pdf", 41_200.0,
            stages = mapOf(
                ProfilerStage.EXTRACT to 40_000.0,
                ProfilerStage.PDF_STRIP to 8_400.0,
                ProfilerStage.PAGE_OCR to 30_900.0,
                ProfilerStage.ROOM to 300.0,
            ),
            pages = listOf(page(84, ocr = 6187.0, render = 120.0, score = 0.21, trusted = false),
                page(85, ocr = 900.0, trusted = false)),
            pageCount = 212,
        )
        val small = doc("note.pdf", 800.0, stages = mapOf(ProfilerStage.EXTRACT to 700.0))
        val d = ProfilerReportMath.diagnosis(listOf(big, small))
        assertEquals(5, d.size)
        assertTrue(d[0].answer.contains("report.pdf"))               // dominating doc named
        assertTrue(d[1].answer.contains("p84"))                      // dominating OCR page named
        assertTrue(d[3].answer.contains(ProfilerStage.PAGE_OCR))     // largest stage named
        assertTrue(d[4].answer.contains("Sprint E4 guard counters")) // E4 reports safe guard hits
    }

    // ── Recorder derived values ───────────────────────────────────────────────

    @Test
    fun `recorder build amortizes strip and derives render and pageOcr sums`() {
        val rec = DocProfileRecorder("uri", "doc.pdf", "pdf", origin = "test")
        rec.pageCount = 4
        rec.stageNs(ProfilerStage.PDF_STRIP, 800_000_000L) // 800ms → 200ms/page share
        rec.pageScored(1, 2_000_000L, 1_000_000L, 0.9, true)
        rec.pageScored(2, 2_000_000L, 1_000_000L, 0.2, false)
        rec.pageRendered(2, 120, 2480, 3508, 34_804_480L)
        rec.beginPageOcr(2)
        rec.ocrTierOutcome(accepted = false)
        rec.pageOcrDone(2, 6187)

        val d = rec.build(10_000, "success")
        assertEquals(4, d.pageCount)
        assertEquals(1, d.trustedPages)
        assertEquals(1, d.ocrFallbackPages)
        assertEquals(0.55, d.avgTrustScore!!, 1e-9)
        assertEquals(2480, d.largestBitmapWidth)
        assertEquals(3508, d.largestBitmapHeight)
        assertEquals(120.0, d.stage(ProfilerStage.RENDER), 1e-9)
        assertEquals(6187.0, d.stage(ProfilerStage.PAGE_OCR), 1e-9)

        val p2 = d.pages.first { it.page == 2 }
        assertEquals(200.0, p2.stripShareMs, 1e-9)
        assertEquals(false, p2.ocrTier1Accepted)
        assertTrue(p2.ocrUsed)
        // pageTotal = stripShare 200 + nfc 2 + trust 1 + render 120 + ocr 6187
        assertEquals(6510.0, p2.pageTotalMs, 1e-9)

        val p1 = d.pages.first { it.page == 1 }
        assertNotNull(p1.trustScore)
        assertEquals(true, p1.trusted)
        assertEquals(203.0, p1.pageTotalMs, 1e-9) // 200 + 2 + 1
    }
}
