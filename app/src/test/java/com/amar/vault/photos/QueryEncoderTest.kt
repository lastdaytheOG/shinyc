package com.amar.vault.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * The app's query encoder gives the vectors the reference pipeline gives (Python: the model's own
 * tokenizer and ONNX Runtime) for the same model file: `photos/query_refs.tsv`, written by
 * `tools/visual-search/make_query_refs.py` from 30 gallery searches in English, Hindi and Hindi in
 * Latin letters. Tokens, prompt, empty picture inputs and pooling all have to be right for this.
 *
 * The model is a local file (hundreds of MB, not in git): without it the test is skipped.
 */
class QueryEncoderTest {

    private val model = File("../tools/visual-search/models/query/onnx/model_quantized.onnx")
    private val table = File("src/test/resources/photos/gemma_bpe.bin")

    @Test
    fun givesTheReferenceVectors() {
        assumeTrue("needs the local model (tools/visual-search/models)", model.exists() && table.exists())
        // ONNX Runtime's desktop library needs the C++ runtime 14.40 or newer; the JDK that ships
        // with Android Studio loads its own 14.29 when it starts, and the test JVM then dies inside
        // msvcp140.dll. On Windows the same check runs on the device instead (PhotoSearchDeviceTest).
        assumeTrue("ONNX Runtime cannot run in Android Studio's JVM on Windows", !isWindows())
        val tokenizer = table.inputStream().use { GemmaTokenizer.read(it) }
        QueryEncoder(model.path, tokenizer).use { encoder ->
            assertEquals(768, encoder.dim)
            val refs = javaClass.classLoader!!.getResourceAsStream("photos/query_refs.tsv")!!
                .bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotEmpty() }
            assertEquals(30, refs.size)
            for (line in refs) {
                val tab = line.lastIndexOf('\t')
                val text = line.substring(0, tab)
                val bytes = Base64.getDecoder().decode(line.substring(tab + 1))
                val ref = FloatArray(bytes.size / 4).also {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it)
                }
                val v = encoder.encode(text)
                var cos = 0f
                for (i in v.indices) cos += v[i] * ref[i]
                assertTrue("cosine $cos for \"$text\"", cos > 0.9999f)
            }
        }
    }
}

internal fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")
