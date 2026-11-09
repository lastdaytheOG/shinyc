package com.amar.vault.indexing

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.IOException

/**
 * Detects whether a PDF page draws any visual imagery or vector outline text:
 * 1. Direct image XObjects (/Subtype /Image).
 * 2. Nested images inside Form XObjects (/Subtype /Form) or patterns.
 * 3. Inline images in the content stream (BI/ID/EI).
 * 4. Dense vector path operations (fill/stroke >= [VECTOR_PATH_THRESHOLD]), which indicate
 *    text converted into vector glyph outlines (certificates, posters, vector invoices).
 */
internal object PdfPageImageDetector {
    /**
     * Threshold of vector path operations (fill, stroke, fillAndStroke).
     * When a page with fewer than 3 text words contains many vector paths,
     * the text is likely drawn as vector glyph outlines and must still route to OCR.
     */
    const val VECTOR_PATH_THRESHOLD: Int = 20

    /**
     * Threshold of curve segments (curveTo).
     * Catches short titles drawn as outline glyphs (e.g. one 7-letter word with curved letter contours)
     * when accompanied by at least [MIN_FILL_FOR_CURVES] fill, while straight divider lines and table
     * borders (0 curves) continue to skip OCR.
     */
    const val VECTOR_CURVE_THRESHOLD: Int = 8
    const val MIN_FILL_FOR_CURVES: Int = 1

    private class VisualContentFoundException : IOException("Visual content detected on page")

    private class Detector(page: PDPage) : PDFGraphicsStreamEngine(page) {
        var hasVisualContent: Boolean = false
        var pathCount: Int = 0
        var fillCount: Int = 0
        var curveCount: Int = 0

        override fun drawImage(pdImage: PDImage?) {
            hasVisualContent = true
            throw VisualContentFoundException()
        }

        override fun strokePath() {
            pathCount++
            checkThresholds()
        }

        override fun fillPath(p0: Path.FillType?) {
            fillCount++
            pathCount++
            checkThresholds()
        }

        override fun fillAndStrokePath(p0: Path.FillType?) {
            fillCount++
            pathCount++
            checkThresholds()
        }

        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            curveCount++
            checkThresholds()
        }

        override fun shadingFill(shadingName: COSName?) {
            hasVisualContent = true
            throw VisualContentFoundException()
        }

        private fun checkThresholds() {
            if (pathCount >= VECTOR_PATH_THRESHOLD || (fillCount >= MIN_FILL_FOR_CURVES && curveCount >= VECTOR_CURVE_THRESHOLD)) {
                hasVisualContent = true
                throw VisualContentFoundException()
            }
        }

        override fun appendRectangle(p0: PointF?, p1: PointF?, p2: PointF?, p3: PointF?) {}
        override fun clip(p0: Path.FillType?) {}
        override fun moveTo(x: Float, y: Float) {}
        override fun lineTo(x: Float, y: Float) {}
        override fun getCurrentPoint(): PointF = PointF()
        override fun closePath() {}
        override fun endPath() {}
    }

    /**
     * True if the page draws any raster images (direct, nested, inline) or contains
     * dense vector paths indicative of text drawn as vector glyph outlines.
     */
    fun hasAnyImageOrVectorOutlines(page: PDPage): Boolean {
        // Fast path: check page resources directly for image XObjects
        val resources = page.resources
        if (resources != null) {
            for (name in resources.xObjectNames) {
                if (resources.isImageXObject(name)) return true
            }
        }

        // Full content stream execution: processes operators to detect inline images (BI/ID/EI),
        // nested Form XObjects, and vector path outline bursts.
        val detector = Detector(page)
        return try {
            detector.processPage(page)
            detector.hasVisualContent
        } catch (e: VisualContentFoundException) {
            true
        } catch (e: Exception) {
            // Fail closed: assume visual content exists on any unexpected parsing error
            true
        }
    }
}
