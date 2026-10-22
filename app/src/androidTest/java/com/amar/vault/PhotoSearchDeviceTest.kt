package com.amar.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.photos.GemmaTokenizer
import com.amar.vault.photos.PhotoEncoder
import com.amar.vault.photos.PhotoModelFiles
import com.amar.vault.photos.PhotoThumbnails
import com.amar.vault.photos.QueryEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Photo search's models give, on Android, the vectors the reference pipeline gives on a desktop
 * for the same model files: the query model on 30 gallery searches (English, Hindi, Hindi in Latin
 * letters) and the photo model on the pictures listed in `photo_refs.tsv`. Tokens, prompt,
 * preprocessing and pooling all have to be right, on the phone's own runtime.
 *
 *     adb shell am instrument -w -e class com.amar.vault.PhotoSearchDeviceTest \
 *         -e photos parity com.amar.vault.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Needs the models in `files/photo-search/` and, beside them, `query_refs.tsv` and
 * `photo_refs.tsv` (from `tools/visual-search/make_query_refs.py` and `export_photo_encoder.py`).
 */
@RunWith(AndroidJUnit4::class)
class PhotoSearchDeviceTest {

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val files = PhotoModelFiles(app)

    private fun vectors(name: String): List<Pair<String, FloatArray>> {
        val f = File(files.dir, name)
        assumeTrue("put $name in files/photo-search", f.exists())
        return f.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }.map { line ->
            val tab = line.lastIndexOf('\t')
            val bytes = Base64.getDecoder().decode(line.substring(tab + 1))
            line.substring(0, tab) to FloatArray(bytes.size / 4).also {
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    @Test
    fun queriesGiveTheReferenceVectors() {
        assumeTrue("run with -e photos parity", InstrumentationRegistry.getArguments().getString("photos") == "parity")
        assumeTrue("photo search models are not on this device", files.present())
        val tokenizer = files.tokenizer().inputStream().use { GemmaTokenizer.read(it) }
        QueryEncoder(files.queryModel().path, tokenizer).use { encoder ->
            val refs = vectors("query_refs.tsv")
            assertEquals(30, refs.size)
            for ((text, ref) in refs) {
                val c = cosine(encoder.encode(text), ref)
                assertTrue("cosine $c for \"$text\"", c > 0.999f)
            }
        }
    }

    @Test
    fun picturesGiveTheReferenceVectors() {
        assumeTrue("run with -e photos parity", InstrumentationRegistry.getArguments().getString("photos") == "parity")
        assumeTrue("photo search models are not on this device", files.present())
        val manifest = files.manifest()!!
        PhotoEncoder(files.photoModel().path, manifest.inputSize).use { encoder ->
            val refs = vectors("photo_refs.tsv")
            assertTrue(refs.isNotEmpty())
            for ((name, ref) in refs) {
                val picture = File(files.dir, "parity/$name")
                val bitmap = PhotoThumbnails.load(app, android.net.Uri.fromFile(picture), side = 512)
                    ?: error("cannot read $picture")
                // The reference was made from the same 512-pixel picture with PIL's bicubic resize,
                // which PhotoPixels reproduces to one step in 255; what is left is JPEG decoding.
                val c = cosine(encoder.encode(bitmap), ref)
                bitmap.recycle()
                assertTrue("cosine $c for $name", c > 0.995f)
            }
        }
    }
}
