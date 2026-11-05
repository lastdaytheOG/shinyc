package com.amar.vault

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.indexing.ExcelFormatExtractor
import com.amar.vault.indexing.SentenceAwareChunker
import com.amar.vault.indexing.WordFormatExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Word and Excel files are read on the device. Run against a slim build (`-PslimApk
 * -PtestPhoneBuild`) it shows that shrinking the code left the Office reader whole: the files
 * are built here, from the smallest parts each format needs, so no test file has to be kept.
 */
@RunWith(AndroidJUnit4::class)
class OfficeFilesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun zip(name: String, parts: Map<String, String>): Uri {
        val file = File(context.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { z ->
            for ((path, text) in parts) {
                z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry()
            }
        }
        return Uri.fromFile(file)
    }

    @Test
    fun aWordFileIsRead() = runBlocking {
        val uri = zip("probe.docx", mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Lantern orchard receipt 4417</w:t></w:r></w:p></w:body></w:document>""",
        ))
        val text = WordFormatExtractor().extract(context, uri, SentenceAwareChunker()).joinToString(" ") { it.text }
        assertTrue("read: $text", "Lantern orchard receipt 4417" in text)
    }

    @Test
    fun anExcelFileIsRead() = runBlocking {
        val uri = zip("probe.xlsx", mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Ledger" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Copper kettle</t></is></c><c r="B1"><v>2981</v></c></row></sheetData></worksheet>""",
        ))
        val text = ExcelFormatExtractor().extract(context, uri, SentenceAwareChunker()).joinToString(" ") { it.text }
        assertTrue("read: $text", "Copper kettle" in text && "2981" in text && "Ledger" in text)
    }
}
