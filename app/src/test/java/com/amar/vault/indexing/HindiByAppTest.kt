package com.amar.vault.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class HindiByAppTest {

    private fun prefs(name: String = "hindi_by_app_test") =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun theAppIsWhatAScreenshotsNameEndsWith() {
        assertEquals(
            "0a1b2c3d4e5f60718293a4b5c6d7e8f9",
            HindiByApp.appOf("Screenshot_2025-01-02-10-20-30-11_0a1b2c3d4e5f60718293a4b5c6d7e8f9.jpg"),
        )
        assertEquals("com.example.chat", HindiByApp.appOf("Screenshot_2025-01-02-10-20-30-123_com.example.chat.jpg"))
        assertEquals("Messages", HindiByApp.appOf("Screenshot_20250102_102030_Messages.jpg"))
        assertEquals("com.example.chat", HindiByApp.appOf("/storage/emulated/0/Pictures/Screenshots/Screenshot_2025-01-02-10-20-30-123_com.example.chat.jpg"))
    }

    @Test
    fun aNameWithoutAnAppGoesWithEveryOtherSuchName() {
        assertEquals(HindiByApp.NO_APP, HindiByApp.appOf("Screenshot_20250102-102030.png"))
        assertEquals(HindiByApp.NO_APP, HindiByApp.appOf("IMG-20250102-WA0001.jpg"))
        assertEquals(HindiByApp.NO_APP, HindiByApp.appOf("IMG_20250102_102030.jpg"))
        assertEquals(HindiByApp.NO_APP, HindiByApp.appOf("12345"))
    }

    @Test
    fun anAppIsReadForHindiUntilItsFirstScreensShowedNone() {
        val byApp = HindiByApp(prefs("learning"))
        repeat(HindiByApp.LEARN) {
            assertTrue(byApp.shouldRead("app"))
            byApp.record("app", false)
        }
        assertFalse(byApp.shouldRead("app"))
        assertTrue(byApp.shouldRead("another app"))
    }

    @Test
    fun hindiSeenOnceIsReadForAsLongAsItIsAmongTheLastScreens() {
        val byApp = HindiByApp(prefs("window"))
        repeat(HindiByApp.LEARN) { byApp.record("app", false) }
        byApp.record("app", true)
        repeat(HindiByApp.WINDOW - 1) {
            assertTrue(byApp.shouldRead("app"))
            byApp.record("app", false)
        }
        assertTrue(byApp.shouldRead("app"))
        byApp.record("app", false)
        assertFalse(byApp.shouldRead("app"))
    }

    @Test
    fun whatWasSeenIsKept() {
        repeat(HindiByApp.LEARN) { HindiByApp(prefs("kept")).record("app", false) }
        assertFalse(HindiByApp(prefs("kept")).shouldRead("app"))
    }
}
