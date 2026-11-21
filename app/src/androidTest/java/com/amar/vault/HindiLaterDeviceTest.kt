package com.amar.vault

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.amar.vault.indexing.BitmapDecoding
import com.amar.vault.indexing.DeeperReadUpkeep
import com.amar.vault.indexing.DeeperReads
import com.amar.vault.indexing.HindiByApp
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.indexing.LineReadingControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HindiLaterDeviceTest {

    @Test
    fun anAppWithoutHindiIsReadInEnglishFirstAndItsHindiIsFoundOnTheCharger() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e hindi check", args.getString("hindi") == "check")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val source = File(args.getString("dir") ?: "/data/local/tmp/pp")
        val names = (args.getString("names")
            ?: "r102.png,r107.png,r111.png,r116.png,r120.png,r13.png,r134.png,r15.png,r203.png,r208.png,r212.png,r221.png,r217.png").split(",")
        val appName = "com.example.benchapp"
        val folder = File(app.filesDir, "hindi-check/Screenshots").apply { mkdirs() }
        val dao = VaultDatabase.get(app).vaultDao()
        val deeper = DeeperReads.get(app)
        val checks = DeeperReads.hindiChecks(app)
        val prefs = app.getSharedPreferences("hindi_by_app", Context.MODE_PRIVATE)
        val byApp = HindiByApp.get(app)
        val pipeline = IndexingPipeline.getInstance(app)
        val extractor = ImageContentExtractor(app)
        val route = LineReadingControl.route
        LineReadingControl.route = LineReadingControl.Route.WHOLE
        val uris = names.mapIndexed { n, name ->
            val copy = File(folder, "Screenshot_2025-01-02-10-20-%02d-00_%s.png".format(n, appName))
            File(source, name).copyTo(copy, overwrite = true)
            Uri.fromFile(copy).toString()
        }
        val nameOf = uris.zip(names).toMap()
        suspend fun forget() {
            dao.getAll().filter { it.uri in uris }.forEach {
                dao.deleteById(it.id)
                deeper.mark(it.id, false)
                checks.mark(it.id, false)
            }
        }
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set usb 0")
        shell("dumpsys battery set status 3")
        try {
            androidx.work.WorkManager.getInstance(app).cancelUniqueWork("deeper_read_upkeep").result.get()
            forget()
            prefs.edit().remove(appName).commit()
            repeat(HindiByApp.LEARN) { byApp.record(appName, false) }
            assertFalse(byApp.shouldRead(appName))

            val expectedDeeper = HashSet<String>()
            val expectedChecks = HashSet<String>()
            val quickText = HashMap<String, String>()
            val fullText = HashMap<String, String>()
            val hindiOn = HashSet<String>()
            val sureHindi = HashSet<String>()
            var withHindiMs = 0.0
            var withoutHindiMs = 0.0
            for ((n, uri) in uris.withIndex()) {
                val bitmap = BitmapDecoding.loadSoftware(app, Uri.parse(uri))!!
                val ways = if (n % 2 == 0) listOf(true, false) else listOf(false, true)
                for (hindi in ways) {
                    val started = System.nanoTime()
                    val content = extractor.extract(bitmap, quick = true, hindi = hindi)
                    val ms = (System.nanoTime() - started) / 1e6
                    if (hindi) withHindiMs += ms else {
                        withoutHindiMs += ms
                        if (content.deeper) expectedDeeper += uri
                        if (content.hindiPutOff) expectedChecks += uri
                        quickText[uri] = content.ocrText
                    }
                }
                val hiFound = extractor.readHindiOnly(bitmap)
                if (ImageContentExtractor.saysHindi(hiFound.text)) hindiOn += uri
                if (ImageContentExtractor.showsHindi(hiFound)) sureHindi += uri
                fullText[uri] = extractor.extract(bitmap).ocrText
                bitmap.recycle()
            }

            val firstStarted = System.nanoTime()
            for (uri in uris) {
                val bitmap = BitmapDecoding.loadSoftware(app, Uri.parse(uri))!!
                pipeline.indexBitmap(bitmap, uri)
                bitmap.recycle()
            }
            val firstMs = (System.nanoTime() - firstStarted) / 1e6
            val first = dao.getAll().filter { it.uri in uris }.associateBy { it.uri }
            assertEquals("every screen stored once", uris.toSet(), first.keys)
            assertEquals("put off in full", expectedDeeper, first.values.filter { it.id in deeper.waiting() }.map { it.uri }.toSet())
            assertEquals("waiting for a Hindi check", expectedChecks, first.values.filter { it.id in checks.waiting() }.map { it.uri }.toSet())
            assertTrue("some screens wait for a Hindi check", expectedChecks.isNotEmpty())
            for (uri in expectedChecks) {
                assertTrue("${nameOf[uri]} stored as read in English", first.getValue(uri).ocrText.startsWith(quickText.getValue(uri)))
            }
            assertFalse("still no Hindi known for the app", byApp.shouldRead(appName))

            val laterStarted = System.nanoTime()
            val result = TestListenableWorkerBuilder<DeeperReadUpkeep>(app).build().doWork()
            val laterMs = (System.nanoTime() - laterStarted) / 1e6
            assertEquals(ListenableWorker.Result.success(), result)
            val later = dao.getAll().filter { it.uri in uris }.associateBy { it.uri }
            assertEquals("nothing left waiting", emptyList<String>(), later.values.filter { it.id in deeper.waiting() || it.id in checks.waiting() }.map { it.uri })
            var foundLater = 0
            for (uri in uris) {
                val stored = later.getValue(uri)
                assertEquals("${nameOf[uri]} keeps its row", first.getValue(uri).id, stored.id)
                when {
                    uri in expectedDeeper || uri in hindiOn -> {
                        assertTrue("${nameOf[uri]} has its full text after the charger", stored.ocrText.startsWith(fullText.getValue(uri)))
                        if (uri in expectedChecks) foundLater++
                    }
                    else -> assertEquals("${nameOf[uri]} kept as read in English", first.getValue(uri).ocrText, stored.ocrText)
                }
            }
            if (expectedChecks.any { it in sureHindi }) assertTrue("Hindi found later makes the app read Hindi again", byApp.shouldRead(appName))
            val report = JSONObject().put("screens", uris.size).put("putOffInFull", expectedDeeper.size)
                .put("hindiChecks", expectedChecks.size).put("hindiFoundLater", foundLater)
                .put("quickWithHindiMs", withHindiMs).put("quickWithoutHindiMs", withoutHindiMs)
                .put("firstMs", firstMs).put("laterMs", laterMs)
                .put("hindiScreens", JSONObject().apply { uris.filter { it in hindiOn }.forEach { put(nameOf.getValue(it), it in expectedChecks) } })
            File(app.getExternalFilesDir(null) ?: app.filesDir, "hindi-check.json").writeText(report.toString())
            android.util.Log.i("HindiLaterDeviceTest", report.toString())
        } finally {
            shell("dumpsys battery reset")
            LineReadingControl.route = route
            forget()
            prefs.edit().remove(appName).commit()
            folder.parentFile?.deleteRecursively()
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { out ->
            java.io.FileInputStream(out.fileDescriptor).use { it.readBytes() }
        }
    }
}
