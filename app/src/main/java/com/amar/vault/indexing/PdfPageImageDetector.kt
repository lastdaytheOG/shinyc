package com.amar.vault.indexing

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import java.io.IOException

/**
 * Detects whether a PDF page draws any image — including direct image XObjects,
 * nested images inside Form XObjects, patterns, and inline images (BI/ID/EI).
 */
internal object PdfPageImageDetector {

    private class ImageFoundException : IOException("Image detected on page")

    private class Detector(page: PDPage) : PDFGraphicsStreamEngine(page) {
        var hasImage: Boolean = false

        override fun drawImage(pdImage: PDImage?) {
            hasImage = true
            throw ImageFoundException()
        }

        override fun appendRectangle(p0: PointF?, p1: PointF?, p2: PointF?, p3: PointF?) {}
        override fun clip(p0: Path.FillType?) {}
        override fun moveTo(x: Float, y: Float) {}
        override fun lineTo(x: Float, y: Float) {}
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
        override fun getCurrentPoint(): PointF = PointF()
        override fun closePath() {}
        override fun endPath() {}
        override fun strokePath() {}
        override fun fillPath(p0: Path.FillType?) {}
        override fun fillAndStrokePath(p0: Path.FillType?) {}
        override fun shadingFill(shadingName: COSName?) {}
    }

    fun hasAnyImage(page: PDPage): Boolean {
        // Fast path: check page resources directly for image XObjects
        val resources = page.resources
        if (resources != null) {
            for (name in resources.xObjectNames) {
                if (resources.isImageXObject(name)) return true
            }
        }

        // Full content stream execution: processes operators to detect inline images (BI/ID/EI)
        // and nested Form XObjects.
        val detector = Detector(page)
        return try {
            detector.processPage(page)
            detector.hasImage
        } catch (e: ImageFoundException) {
            true
        } catch (e: Exception) {
            // Fail closed: assume image exists on any unexpected parsing error
            true
        }
    }
}
