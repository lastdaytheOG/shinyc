package com.amar.vault

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.amar.vault.indexing.BitmapDecoding
import com.amar.vault.indexing.DeeperReadUpkeep
import com.amar.vault.indexing.DeeperReads
import com.amar.vault.indexing.ImageContentExtractor
import com.amar.vault.indexing.LineReadingControl
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeeperReadDeviceTest {

    @Test
    fun screensPutOffAtFirstAreReadInFullOnTheCharger() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("run with -e deeper check", args.getString("deeper") == "check")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val source = File(args.getString("dir") ?: "/data/local/tmp/pp")
        val names = (args.getString("names") ?: "r114.png,r202.png,r214.png,r227.png,r24.png,r314.png,r1.png,r126.png,r219.png,r312.png").split(",")
        val folder = File(app.filesDir, "deeper-check/Screenshots").apply { mkdirs() }
        val dao = VaultDatabase.get(app).vaultDao()
        val waiting = DeeperReads.get(app)
        val pipeline = IndexingPipeline.getInstance(app)
        val extractor = ImageContentExtractor(app)
        val route = LineReadingControl.route
        LineReadingControl.route = LineReadingControl.Route.WHOLE
        val uris = names.map { name ->
            val copy = File(folder, "Screenshot_$name")
            File(source, name).copyTo(copy, overwrite = true)
            Uri.fromFile(copy).toString()
        }
        shell("dumpsys battery set ac 0")
        shell("dumpsys battery set usb 0")
        shell("dumpsys battery set status 3")
        try {
            androidx.work.WorkManager.getInstance(app).cancelUniqueWork("deeper_read_upkeep").result.get()
            dao.getAll().filter { it.uri in uris }.forEach {
                dao.deleteById(it.id)
                waiting.mark(it.id, false)
            }
            val expectedPutOff = HashSet<String>()
            val full = HashMap<String, String>()
            for (uri in uris) {
                val bitmap = BitmapDecoding.loadSoftware(app, Uri.parse(uri))!!
                if (extractor.extract(bitmap, quick = true).deeper) expectedPutOff += uri
                full[uri] = extractor.extract(bitmap).ocrText
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
            val putOff = first.values.filter { it.id in waiting.waiting() }.map { it.uri }.toSet()
            assertEquals("waiting are the screens a quick read puts off", expectedPutOff, putOff)
            assertTrue("some screens put off", putOff.isNotEmpty())
            for (uri in uris - putOff) {
                assertTrue("$uri has its full text at once", first.getValue(uri).ocrText.startsWith(full.getValue(uri)))
            }

            val laterStarted = System.nanoTime()
            val result = TestListenableWorkerBuilder<DeeperReadUpkeep>(app).build().doWork()
            val laterMs = (System.nanoTime() - laterStarted) / 1e6
            val later = dao.getAll().filter { it.uri in uris }.associateBy { it.uri }
            val stillWaiting = later.values.filter { it.id in waiting.waiting() }.map { it.uri }
            assertEquals("nothing left waiting", emptyList<String>(), stillWaiting)
            assertEquals(ListenableWorker.Result.success(), result)
            var changed = 0
            for (uri in putOff) {
                val stored = later.getValue(uri)
                assertEquals("$uri keeps its row", first.getValue(uri).id, stored.id)
                assertEquals("$uri keeps its date", first.getValue(uri).timestamp, stored.timestamp)
                assertTrue("$uri has its full text after the charger read", stored.ocrText.startsWith(full.getValue(uri)))
                if (stored.ocrText != first.getValue(uri).ocrText) changed++
            }
            val report = JSONObject().put("screens", uris.size).put("putOff", putOff.size).put("firstMs", firstMs)
                .put("laterMs", laterMs).put("textChangedByLaterRead", changed)
            File(app.getExternalFilesDir(null) ?: app.filesDir, "deeper-check.json").writeText(report.toString())
            android.util.Log.i("DeeperReadDeviceTest", report.toString())
        } finally {
            shell("dumpsys battery reset")
            LineReadingControl.route = route
            dao.getAll().filter { it.uri in uris }.forEach {
                dao.deleteById(it.id)
                waiting.mark(it.id, false)
            }
            folder.parentFile?.deleteRecursively()
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { out ->
            java.io.FileInputStream(out.fileDescriptor).use { it.readBytes() }
        }
    }
}
