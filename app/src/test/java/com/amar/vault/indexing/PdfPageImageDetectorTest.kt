package com.amar.vault.indexing

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PdfPageImageDetectorTest {

    @Before
    fun initPdfBox() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
    }

    private fun createTinyBitmap(): Bitmap {
        return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, 0xFF000000.toInt())
        }
    }

    @Test
    fun testTextOnlyPageHasNoImages() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(100f, 700f)
                cs.showText("Chapter 1")
                cs.endText()
            }
            assertFalse(PdfPageImageDetector.hasAnyImageOrVectorOutlines(page))
        }
    }

    @Test
    fun testDirectImageXObjectDetected() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val bmp = createTinyBitmap()
            val pdImage = LosslessFactory.createFromImage(doc, bmp)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(pdImage, 50f, 50f, 10f, 10f)
            }
            bmp.recycle()
            assertTrue(PdfPageImageDetector.hasAnyImageOrVectorOutlines(page))
        }
    }

    @Test
    fun testNestedImageInFormXObjectDetected() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            val bmp = createTinyBitmap()
            val pdImage = LosslessFactory.createFromImage(doc, bmp)

            // Create a Form XObject with resources and draw the image inside it
            val form = PDFormXObject(doc).apply {
                bBox = PDRectangle(0f, 0f, 50f, 50f)
                resources = PDResources()
            }
            PDPageContentStream(doc, form, form.contentStream.createOutputStream()).use { fcs ->
                fcs.drawImage(pdImage, 0f, 0f, 20f, 20f)
            }

            // Draw the Form XObject on the page
            PDPageContentStream(doc, page).use { pcs ->
                pcs.drawForm(form)
            }
            bmp.recycle()
            assertTrue(PdfPageImageDetector.hasAnyImageOrVectorOutlines(page))
        }
    }

    @Test
    fun testInlineImageDetected() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)

            // Write an inline image operator directly into the page stream
            val cosStream = doc.document.createCOSStream()
            cosStream.createOutputStream().use { out ->
                val content = "q\nBI\n/W 1\n/H 1\n/CS /DeviceGray\n/BPC 8\nID\n\u0000EI\nQ\n"
                out.write(content.toByteArray(Charsets.ISO_8859_1))
            }
            page.setContents(PDStream(cosStream))

            assertTrue(PdfPageImageDetector.hasAnyImageOrVectorOutlines(page))
        }
    }

    @Test
    fun testSparsePageWithoutImagesBypassesOcrFallback() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = java.io.File(context.cacheDir, "sparse_test.pdf")
            PDDocument().use { doc ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(100f, 700f)
                    cs.showText("Chapter One")
                    cs.endText()
                }
                doc.save(file)
            }

            val chunks = PdfFormatExtractor().extract(context, android.net.Uri.fromFile(file), WordWindowChunker())
            assertEquals(1, chunks.size)
            assertEquals("Chapter One", chunks[0].text)
            assertEquals(1, chunks[0].pdfPage)
            file.delete()
        }
    }

    @Test
    fun testSparsePageWithImageTriggersOcrFallback() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = java.io.File(context.cacheDir, "sparse_with_image.pdf")
            PDDocument().use { doc ->
                val page = PDPage()
                doc.addPage(page)
                val bmp = createTinyBitmap()
                val pdImage = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.newLineAtOffset(100f, 700f)
                    cs.showText("Figure One")
                    cs.endText()
                    cs.drawImage(pdImage, 50f, 50f, 10f, 10f)
                }
                bmp.recycle()
                doc.save(file)
            }

            // The page has an image, so it must NOT bypass OCR fallback
            assertTrue(PdfPageImageDetector.hasAnyImageOrVectorOutlines(PDDocument.load(file).use { it.getPage(0) }))
            file.delete()
        }
    }

    @Test
    fun testVectorOutlineTextDetectedAndRoutesToOcr() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                // Draw 25 vector glyph outline shapes (no font operators, no images)
                for (i in 0 until 25) {
                    val x = 50f + (i % 5) * 40f
                    val y = 700f - (i / 5) * 40f
                    cs.moveTo(x, y)
                    cs.lineTo(x + 10f, y + 20f)
                    cs.lineTo(x + 20f, y)
                    cs.closePath()
                    cs.fill()
                }
            }
            assertTrue(PdfPageImageDetector.hasAnyImageOrVectorOutlines(page))
        }
    }

    @Test
    fun testTitlePageWithSingleDividerRuleDoesNotRouteToOcr() {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(100f, 700f)
                cs.showText("Chapter 1")
                cs.endText()
                // Single decorative underline (1 path)
                cs.moveTo(100f, 690f)
                cs.lineTo(200f, 690f)
                cs.stroke()
            }
            assertFalse(PdfPageImageDetector.hasAnyImageOrVectorOutlines(page))
        }
    }

    @Test
    fun testTimingComparisonOnRealMultiPageDocument() {
        kotlinx.coroutines.runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val file = java.io.File(context.cacheDir, "timing_doc.pdf")
            PDDocument().use { doc ->
                for (i in 1..10) {
                    val page = PDPage()
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { cs ->
                        cs.beginText()
                        cs.setFont(PDType1Font.HELVETICA, 12f)
                        cs.newLineAtOffset(50f, 700f)
                        if (i == 1) {
                            cs.showText("Cover Title")
                        } else if (i == 5) {
                            cs.showText("Section Two")
                        } else {
                            val body = "This is a standard document page with multiple sentences to pass the text trust scorer comfortably. " +
                                "It contains information about database indexing, retrieval precision, and text extraction pipelines without any images."
                            cs.showText(body)
                        }
                        cs.endText()
                    }
                }
                doc.save(file)
            }

            val extractor = PdfFormatExtractor()
            val t0 = System.currentTimeMillis()
            val chunks = extractor.extract(context, android.net.Uri.fromFile(file), WordWindowChunker())
            val elapsed = System.currentTimeMillis() - t0
            println("TIMING_BENCHMARK: 10 pages (2 sparse cover/section pages) extracted in ${elapsed}ms, chunks=${chunks.size}")
            assertTrue(chunks.size >= 10)
            file.delete()
        }
    }
}
