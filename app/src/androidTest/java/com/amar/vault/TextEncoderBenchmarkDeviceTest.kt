package com.amar.vault

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.amar.vault.benchmark.TextEncoderBenchmark
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TextEncoderBenchmarkDeviceTest {

    private val app by lazy { InstrumentationRegistry.getInstrumentation().targetContext.applicationContext }

    @Test
    fun measure() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e textencoder measure", args.getString("textencoder") == "measure")
        val label = args.getString("label") ?: "run"
        val runs = args.getString("runs")?.toInt() ?: 10
        val threads = args.getString("threads")?.toInt() ?: 4
        val scan = args.getString("scan") != "off"
        val line = JSONObject()
            .put("label", label)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("hardware", Build.HARDWARE)
            .put("abi", Build.SUPPORTED_ABIS.first())
            .put("emulator", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
            .put("cores", Runtime.getRuntime().availableProcessors())
            .put("encoder", TextEncoderBenchmark.measureEncoder(app, runs, threads))
        if (scan) line.put("scan", TextEncoderBenchmark.measureScan())
        android.util.Log.i("TextEncoderBench", line.toString())
        File(app.filesDir, "text-encoder").apply { mkdirs() }.resolve("result.jsonl").appendText(line.toString() + "\n")
    }
}
