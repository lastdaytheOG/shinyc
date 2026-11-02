package com.amar.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** What the Saved screen left beside the database goes once, and nothing else goes with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class SavedLeftoversTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(path: String) = File(context.filesDir, path).apply {
        parentFile?.mkdirs()
        writeText(path)
    }

    @Test
    fun itsFilesGoAndTheRestStay() {
        val prefs = file("datastore/saved_prefs.preferences_pb")
        val thumbnail = file("thumbnails/entry-1.jpg")
        val otherPrefs = file("datastore/scan_prefs.preferences_pb")
        val sharedFile = file("shared_imports/Bill.pdf")

        SavedLeftovers.clearOnce(context)

        assertFalse(prefs.exists())
        assertFalse(File(context.filesDir, "thumbnails").exists())
        assertFalse(thumbnail.exists())
        assertTrue("another screen's settings stay", otherPrefs.exists())
        assertTrue("a file that was shared in stays", sharedFile.exists())
    }

    @Test
    fun itIsDoneOnce() {
        SavedLeftovers.clearOnce(context)
        val later = file("thumbnails/not-from-saved.jpg")

        SavedLeftovers.clearOnce(context)

        assertTrue(later.exists())
        assertEquals("not-from-saved.jpg", later.name)
    }
}
