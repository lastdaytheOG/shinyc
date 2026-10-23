package com.amar.vault.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

/**
 * Photo search's models are copied out of the APK the first time, and again when an update carries
 * other ones: the new photo model is copied, the old one's file goes, the files both name stay as
 * they are, and a copy that fails leaves the models already on the phone in use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PhotoModelFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun manifest(id: String, photo: String, sameSpaceAs: List<String> = emptyList()) =
        """{"id": "$id", "dim": 768, "inputSize": 256, "queryModel": "query.onnx", "photoModel": "$photo"""" +
            (if (sameSpaceAs.isEmpty()) "" else """, "sameSpaceAs": [${sameSpaceAs.joinToString { "\"$it\"" }}]""") + "}"

    /** An APK's photo-search assets, with a folder of test pictures beside the models as the real one has. */
    private class Apk(val files: Map<String, String>, val broken: Set<String> = emptySet()) : CarriedFiles {
        override fun list(): Set<String> = if (files.isEmpty()) emptySet() else files.keys + "parity"
        override fun open(name: String) =
            if (name in broken) throw FileNotFoundException(name)
            else files[name]?.byteInputStream() ?: throw FileNotFoundException(name)
    }

    private fun apk(manifest: String, photo: String, broken: Set<String> = emptySet()) = Apk(
        mapOf(
            "manifest.json" to manifest, "gemma_bpe.bin" to "tokenizer", "query.onnx" to "query",
            "query.onnx_data" to "query weights", photo to "photo model $photo",
        ),
        broken,
    )

    private fun File.text(name: String) = File(this, name).readText()

    @Test fun theFirstTimeEveryModelIsCopiedOut() {
        val dir = tmp.newFolder("photo-search")
        val files = PhotoModelFiles(dir, apk(manifest("a", "photo-a.onnx"), "photo-a.onnx"))
        assertTrue(files.installFromApk())
        assertEquals("a", files.manifest()!!.id)
        assertEquals("photo model photo-a.onnx", dir.text("photo-a.onnx"))
        assertEquals("query weights", dir.text("query.onnx_data"))
        assertFalse("test pictures stay in the APK", File(dir, "parity").exists())
    }

    @Test fun anUpdateCopiesTheNewModelAndRemovesTheOldOne() {
        val dir = tmp.newFolder("photo-search")
        PhotoModelFiles(dir, apk(manifest("a", "photo-a.onnx"), "photo-a.onnx")).installFromApk()
        File(dir, "query.onnx").writeText("query as copied before")

        val files = PhotoModelFiles(dir, apk(manifest("b", "photo-b.onnx", listOf("a")), "photo-b.onnx"))
        assertTrue(files.installFromApk())
        assertEquals("b", files.manifest()!!.id)
        assertEquals(listOf("a"), files.manifest()!!.sameSpaceAs)
        assertEquals("photo model photo-b.onnx", dir.text("photo-b.onnx"))
        assertFalse("the replaced photo model's 64 MB go", File(dir, "photo-a.onnx").exists())
        assertEquals("a file both name is not copied again", "query as copied before", dir.text("query.onnx"))
        assertTrue(files.photoModel().exists())
    }

    @Test fun theSameModelsAreLeftAlone() {
        val dir = tmp.newFolder("photo-search")
        val carried = apk(manifest("a", "photo-a.onnx"), "photo-a.onnx")
        PhotoModelFiles(dir, carried).installFromApk()
        File(dir, "photo-a.onnx").writeText("as on the phone")
        assertTrue(PhotoModelFiles(dir, carried).installFromApk())
        assertEquals("as on the phone", dir.text("photo-a.onnx"))
    }

    @Test fun anUpdateThatCannotBeCopiedKeepsTheModelsInUse() {
        val dir = tmp.newFolder("photo-search")
        PhotoModelFiles(dir, apk(manifest("a", "photo-a.onnx"), "photo-a.onnx")).installFromApk()
        val files = PhotoModelFiles(dir, apk(manifest("b", "photo-b.onnx"), "photo-b.onnx", broken = setOf("photo-b.onnx")))
        assertTrue(files.installFromApk())
        assertEquals("a", files.manifest()!!.id)
        assertTrue(File(dir, "photo-a.onnx").exists())
        assertFalse(File(dir, "photo-b.onnx").exists())
        assertFalse(File(dir, "photo-b.onnx.part").exists())
    }

    @Test fun aModelFileThatWentMissingIsCopiedAgain() {
        val dir = tmp.newFolder("photo-search")
        val carried = apk(manifest("a", "photo-a.onnx"), "photo-a.onnx")
        PhotoModelFiles(dir, carried).installFromApk()
        File(dir, "photo-a.onnx").delete()
        assertTrue(PhotoModelFiles(dir, carried).installFromApk())
        assertTrue(File(dir, "photo-a.onnx").exists())
    }

    @Test fun withoutModelsInTheApkThoseOnThePhoneAreUsed() {
        val dir = tmp.newFolder("photo-search")
        PhotoModelFiles(dir, apk(manifest("adb", "photo.onnx"), "photo.onnx")).installFromApk()
        val files = PhotoModelFiles(dir, Apk(emptyMap()))
        assertFalse(files.bundled())
        assertTrue(files.installFromApk())
        assertEquals("adb", files.manifest()!!.id)
    }
}
