package com.amar.vault

import android.content.ComponentName
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Three PDFs are shared to the app in one go, through the real share sheet: the intent
 * another app would send, the "Save to Vault" button pressed, the real background work.
 * Each of the three ends up saved and searchable. (Before, the first was and the other two
 * were dropped.)
 *
 * The files are drawn here, and everything the test stores it removes again.
 */
@RunWith(AndroidJUnit4::class)
class ShareSeveralFilesDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val db = VaultDatabase.get(context)
    private val bm25: com.amar.vault.retrieval.Bm25Index = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext, com.amar.vault.retrieval.Bm25IndexEntryPoint::class.java
    ).bm25Index()
    private val folder = File(context.filesDir, "share-test").apply { mkdirs() }

    /** File name → a word only that file says. */
    private val files = linkedMapOf(
        "Probe rent agreement.pdf" to "zanzibarrent",
        "Probe power bill.pdf" to "quillonpower",
        "Probe school fees.pdf" to "marzipanfees",
    )

    private fun textPdf(name: String, word: String): File {
        val file = File(folder, name)
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f }
        for (number in 1..2) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, number).create())
            page.canvas.drawText("Statement for the $word account, sheet $number", 60f, 120f, paint)
            page.canvas.drawText("The amount is due within seven working days of this notice.", 60f, 150f, paint)
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
        return file
    }

    /** The node under [node] that shows [label]. (Asking the tree to find text finds nothing in a Compose screen.) */
    private fun showing(node: android.view.accessibility.AccessibilityNodeInfo, label: String): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { showing(it, label) }
            if (found != null) return found
        }
        return null
    }

    /** Taps the middle of whatever on screen shows [label], as a finger would. */
    private fun press(label: String, withinMs: Long): Boolean {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val until = SystemClock.uptimeMillis() + withinMs
        while (SystemClock.uptimeMillis() < until) {
            val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
            val node = roots.firstNotNullOfOrNull { showing(it, label) }
            if (node != null) {
                val place = android.graphics.Rect().also(node::getBoundsInScreen)
                val at = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    val event = android.view.MotionEvent.obtain(at, at + 40, action, place.exactCenterX(), place.exactCenterY(), 0)
                    event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                    automation.injectInputEvent(event, true)
                    event.recycle()
                    SystemClock.sleep(60)
                }
                return true
            }
            SystemClock.sleep(300)
        }
        return false
    }

    private suspend fun savedByThisTest(): List<VaultItem> =
        db.vaultDao().getAll().filter { it.parentDocumentId == null && it.sourceFile in files.keys }

    @After
    fun cleanUp(): Unit = runBlocking {
        for (item in savedByThisTest()) {
            // The pieces and the list entry first (absent in a build from before the import list).
            runCatching {
                val pieces = db.vaultDao().chunkIdsOfDocument(item.id)
                db.vaultDao().deleteChunksOfDocument(item.id)
                bm25.removeDocuments(pieces)
            }
            runCatching { db.vaultDocumentDao().getById(item.id)?.let { db.vaultDocumentDao().deleteByContentHash(it.contentHash) } }
            runCatching { com.amar.vault.indexing.PdfSourceReuseCache.forget(context, item.contentHash) }
            runCatching { db.documentImportDao().delete(item.id) }
            db.vaultDao().deleteById(item.id)   // its Saved entry goes with it
            bm25.removeDocument(item.id)
            File(item.uri).delete()
        }
        folder.deleteRecursively()
        Unit
    }

    @Test
    fun threePdfsSharedTogetherAreAllSavedAndSearchable() = runBlocking {
        val made = files.map { (name, word) -> textPdf(name, word) }
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            component = ComponentName(context, ShareHandlerActivity::class.java)
            type = "application/pdf"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(made.map { Uri.fromFile(it) }))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(share)
        assertTrue("the share sheet came up with its Save button", press("Save to Vault", withinMs = 20_000))

        // Saved: one item for each file.
        val until = SystemClock.uptimeMillis() + 40_000
        var saved = savedByThisTest()
        while (saved.size < files.size && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(500)
            saved = savedByThisTest()
        }
        Log.i(TAG, "saved: ${saved.map { it.sourceFile }}")
        assertEquals("each shared file is an item of its own", files.keys.sorted(), saved.map { it.sourceFile }.sorted())
        val entries = db.stashItemDao().getStashItemsByType("SAVED").first().filter { entry -> saved.any { it.id == entry.vaultItemId } }
        assertEquals("…with its own Saved entry", files.size, entries.size)
        assertEquals("…all in the one folder that was chosen", 1, entries.map { it.category }.toSet().size)

        // Read: each one's own word finds that file.
        val readBy = SystemClock.uptimeMillis() + 120_000
        fun found(word: String, item: VaultItem) = bm25.search(word, 20).any { it.startsWith(item.id + "_chunk") }
        while (SystemClock.uptimeMillis() < readBy && !saved.all { found(files.getValue(it.sourceFile), it) }) {
            SystemClock.sleep(500)
        }
        for (item in saved) {
            val word = files.getValue(item.sourceFile)
            assertTrue("\"$word\" finds ${item.sourceFile}", found(word, item))
            val import = db.documentImportDao().getById(item.id)
            assertEquals("${item.sourceFile} is on the list as read", com.amar.vault.indexing.ImportState.DONE, import?.state)
            assertEquals(2, import?.pageCount)
        }
    }

    private companion object { const val TAG = "ShareSeveralTest" }
}
