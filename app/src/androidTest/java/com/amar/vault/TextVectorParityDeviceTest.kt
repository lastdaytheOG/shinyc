package com.amar.vault

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.TextEncoderBenchmark
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class TextVectorParityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app by lazy { instrumentation.targetContext.applicationContext }

    @Test
    fun storedTextVectorsMatchThePcReference() {
        val loaded = TextEncoderBenchmark.encoder(app)
        assumeTrue("the model pack is not on this device", loaded != null)
        val (encoder, _) = loaded!!
        val lines = instrumentation.context.assets.open("passage_refs.tsv").bufferedReader().readLines().filter { it.isNotBlank() }
        val report = JSONObject()
        var worst = 1.0
        encoder.use { enc ->
            lines.forEachIndexed { n, line ->
                val f = line.split('\t')
                val title = f[0].ifEmpty { null }
                val text = f[1].replace("\\n", "\n")
                val maxTokens = f[2].toInt()
                val ids = enc.passageIds(title, text, maxTokens)
                assertEquals("token count of passage $n", f[3].toInt(), ids.size)
                val ref = ByteBuffer.wrap(Base64.decode(f[4], Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val v = enc.passage(title, text, maxTokens)
                var dot = 0.0
                for (i in v.indices) dot += v[i] * ref.get(i)
                report.put("p$n", dot)
                worst = minOf(worst, dot)
            }
        }
        report.put("worst", worst)
        File(app.filesDir, "text-encoder").apply { mkdirs() }.resolve("parity.json").writeText(report.toString())
        android.util.Log.i("TextVectorParity", report.toString())
        assertTrue("cosine to the PC reference fell to $worst", worst > 0.999)
    }
}
