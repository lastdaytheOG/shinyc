package com.amar.vault.indexing.ppocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LineModelFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val manifest = """
        {
          "id": "finder-and-three-readers",
          "version": 3,
          "finder": {"model": "finder.onnx", "longSide": 960, "thresh": 0.25, "boxThresh": 0.5, "unclip": 1.6},
          "readers": {
            "all": {"model": "all.onnx", "letters": "all.txt"},
            "numbers": {"model": "numbers.onnx", "letters": "numbers.txt"},
            "hindi": {"model": "hindi.onnx", "letters": "hindi.txt"}
          }
        }
    """.trimIndent()

    private fun put(name: String, text: String = "x") = File(folder.root, name).writeText(text)

    private fun putEverything() {
        put("manifest.json", manifest)
        for (name in listOf("finder.onnx", "all.onnx", "all.txt", "numbers.onnx", "numbers.txt", "hindi.onnx", "hindi.txt")) put(name)
    }

    @Test
    fun withoutAManifestThereAreNoReaders() {
        val files = LineModelFiles(folder.root)

        assertNull(files.manifest())
        assertFalse(files.present())
    }

    @Test
    fun theManifestSaysWhichFilesAndWhichSettings() {
        putEverything()
        val files = LineModelFiles(folder.root)
        val read = files.manifest()!!

        assertEquals("finder-and-three-readers", read.id)
        assertEquals(3, read.version)
        assertEquals(960, read.finder.longSide)
        assertEquals(0.25f, read.finder.thresh, 0f)
        assertEquals(0.5f, read.finder.boxThresh, 0f)
        assertEquals(1.6f, read.finder.unclip, 0f)
        assertEquals(File(folder.root, "finder.onnx"), files.finder())
        assertEquals(File(folder.root, "numbers.onnx"), files.reader(LineModelFiles.NUMBERS))
        assertTrue(files.present())
    }

    @Test
    fun oneMissingFileMeansTheReadersAreNotThere() {
        putEverything()
        File(folder.root, "hindi.txt").delete()

        assertFalse(LineModelFiles(folder.root).present())
    }

    @Test
    fun aManifestWithoutOneOfTheThreeReadersIsNotEnough() {
        putEverything()
        put("manifest.json", manifest.replace("\"hindi\": {\"model\": \"hindi.onnx\", \"letters\": \"hindi.txt\"}", "\"other\": {\"model\": \"hindi.onnx\", \"letters\": \"hindi.txt\"}"))

        assertFalse(LineModelFiles(folder.root).present())
    }

    @Test
    fun aBrokenManifestIsNoManifest() {
        put("manifest.json", "{ not json")

        assertNull(LineModelFiles(folder.root).manifest())
    }

    @Test
    fun lettersAreOneToALineAndALetterThatIsASpaceIsKept() {
        putEverything()
        val wide = Char(0x3000).toString()
        put("all.txt", listOf("a", wide, "#", "क").joinToString("\n", postfix = "\n"))

        assertEquals(listOf("a", wide, "#", "क"), LineModelFiles(folder.root).letters(LineModelFiles.ALL))
    }

    @Test
    fun lettersWrittenWithWindowsLineEndsAreTheSameLetters() {
        assertEquals(listOf("a", "b"), LineModelFiles.lettersIn("a\r\nb\r\n"))
        assertEquals(listOf("a", "b"), LineModelFiles.lettersIn("a\nb"))
    }
}
