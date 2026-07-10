package com.amar.vault

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ContentOpenManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun createVaultItem(url: String): VaultItem {
        return VaultItem(
            id = UUID.randomUUID().toString(),
            uri = url,
            ocrText = "",
            lang = "en",
            itemType = "LINK",
            timestamp = System.currentTimeMillis(),
            originalUri = url
        )
    }

    private fun registerAppHandler(url: String, packageName: String) {
        val shadowPackageManager = shadowOf(context.packageManager)

        // Generic query handler
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val resolveInfoGeneric = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = "FakeActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(intent, resolveInfoGeneric)

        // Specific package handler
        val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { setPackage(packageName) }
        val resolveInfoApp = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                this.name = "FakeActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(appIntent, resolveInfoApp)
    }

    private fun registerBrowserHandler(url: String) {
        val shadowPackageManager = shadowOf(context.packageManager)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = "com.android.browser"
                this.name = "BrowserActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(intent, resolveInfo)
    }

    @Test
    fun testAndroidIntentUriRouting() {
        val intentUri = "intent://some_path#Intent;scheme=myscheme;package=com.example.app;end"
        
        // Register handler for the parsed intent URI. The resolver adds
        // CATEGORY_BROWSABLE (correct for deep links), so the registered intent
        // must carry it too for Robolectric's explicit-intent matcher.
        val shadowPackageManager = shadowOf(context.packageManager)
        val parsedIntent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val resolveInfo = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.app"
                name = "FakeActivity"
            }
        }
        shadowPackageManager.addResolveInfoForIntent(parsedIntent, resolveInfo)

        val item = createVaultItem(intentUri)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull("Should start an activity", startedIntent)
        assertEquals("com.example.app", startedIntent.`package`)
    }

    @Test
    fun testPlayStoreLink_AppInstalled() {
        val playStoreUrl = "https://play.google.com/store/apps/details?id=com.amar.vault"
        registerAppHandler(playStoreUrl, "com.android.vending")

        val item = createVaultItem(playStoreUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.android.vending", startedIntent.`package`)
        assertEquals(playStoreUrl, startedIntent.dataString)
    }

    @Test
    fun testPlayStoreLink_AppNotInstalled() {
        val playStoreUrl = "https://play.google.com/store/apps/details?id=com.amar.vault"
        registerBrowserHandler(playStoreUrl)

        val item = createVaultItem(playStoreUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertNull("Browser fallback should not set target package", startedIntent.`package`)
        assertEquals(playStoreUrl, startedIntent.dataString)
    }

    @Test
    fun testPlayStoreMarketLink_AppNotInstalled() {
        val marketUrl = "market://details?id=com.amar.vault"
        val fallbackUrl = "https://play.google.com/store/apps/details?id=com.amar.vault"
        registerBrowserHandler(fallbackUrl)

        val item = createVaultItem(marketUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertNull("Browser fallback should not set target package", startedIntent.`package`)
        assertEquals(fallbackUrl, startedIntent.dataString)
    }

    @Test
    fun testInstagram_AppInstalled() {
        val instagramUrl = "https://instagram.com/reel/123"
        registerAppHandler(instagramUrl, "com.instagram.android")

        val item = createVaultItem(instagramUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.instagram.android", startedIntent.`package`)
    }

    @Test
    fun testInstagram_AppNotInstalled() {
        val instagramUrl = "https://instagram.com/reel/123"
        registerBrowserHandler(instagramUrl)

        val item = createVaultItem(instagramUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertNull(startedIntent.`package`)
        assertEquals(instagramUrl, startedIntent.dataString)
    }

    @Test
    fun testYouTube_AppInstalled() {
        val youtubeUrl = "https://www.youtube.com/watch?v=123"
        registerAppHandler(youtubeUrl, "com.google.android.youtube")

        val item = createVaultItem(youtubeUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.google.android.youtube", startedIntent.`package`)
    }

    @Test
    fun testYouTube_AppNotInstalled() {
        val youtubeUrl = "https://www.youtube.com/watch?v=123"
        registerBrowserHandler(youtubeUrl)

        val item = createVaultItem(youtubeUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertNull(startedIntent.`package`)
        assertEquals(youtubeUrl, startedIntent.dataString)
    }

    @Test
    fun testReddit_AppInstalled() {
        val redditUrl = "https://reddit.com/r/android"
        registerAppHandler(redditUrl, "com.reddit.frontpage")

        val item = createVaultItem(redditUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.reddit.frontpage", startedIntent.`package`)
    }

    @Test
    fun testSpotify_AppInstalled() {
        val spotifyUrl = "spotify:track:123"
        registerAppHandler(spotifyUrl, "com.spotify.music")

        val item = createVaultItem(spotifyUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.spotify.music", startedIntent.`package`)
    }

    @Test
    fun testSpotify_AppNotInstalled() {
        val spotifyUrl = "spotify:track:123"
        val fallbackUrl = "https://open.spotify.com/track/123"
        registerBrowserHandler(fallbackUrl)

        val item = createVaultItem(spotifyUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertNull(startedIntent.`package`)
        assertEquals(fallbackUrl, startedIntent.dataString)
    }

    @Test
    fun testAmazon_AppInstalled() {
        val amazonUrl = "https://www.amazon.com/dp/123"
        registerAppHandler(amazonUrl, "com.amazon.mShop.android.shopping")

        val item = createVaultItem(amazonUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.amazon.mShop.android.shopping", startedIntent.`package`)
    }

    @Test
    fun testFlipkart_AppInstalled() {
        val flipkartUrl = "https://flipkart.com/product/123"
        registerAppHandler(flipkartUrl, "com.flipkart.android")

        val item = createVaultItem(flipkartUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.flipkart.android", startedIntent.`package`)
    }

    @Test
    fun testLinkedIn_AppInstalled() {
        val linkedinUrl = "https://linkedin.com/in/someone"
        registerAppHandler(linkedinUrl, "com.linkedin.android")

        val item = createVaultItem(linkedinUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.linkedin.android", startedIntent.`package`)
    }

    @Test
    fun testGitHub_PreferBrowser() {
        val githubUrl = "https://github.com/google/dagger"
        registerBrowserHandler(githubUrl)

        val item = createVaultItem(githubUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertNull("GitHub should directly route to browser with no package set", startedIntent.`package`)
        assertEquals(githubUrl, startedIntent.dataString)
    }

    @Test
    fun testUnknownUrl_LetAndroidResolve() {
        val unknownUrl = "https://example.com/hello"
        registerBrowserHandler(unknownUrl)

        val item = createVaultItem(unknownUrl)
        ContentOpenManager.open(context, item)

        val shadowApp = shadowOf(context as android.app.Application)
        val startedIntent = shadowApp.nextStartedActivity
        assertNotNull(startedIntent)
        assertNull("Unknown URL should route to browser without package", startedIntent.`package`)
        assertEquals(unknownUrl, startedIntent.dataString)
    }

    // ── Embedded URL in caption text (Instagram/YouTube share style) ──

    private fun createEmbeddedTextItem(caption: String): VaultItem = VaultItem(
        id = UUID.randomUUID().toString(),
        uri = caption,
        ocrText = caption,
        lang = "en",
        itemType = "LINK",
        timestamp = System.currentTimeMillis(),
        originalUri = caption,
    )

    @Test
    fun testEmbeddedInstagramUrl_routesToInstagramApp() {
        val realUrl = "https://www.instagram.com/reel/CxYz"
        registerAppHandler(realUrl, "com.instagram.android")

        // Instagram shares text like: "caption ... <url>"
        val item = createEmbeddedTextItem("Loved this reel 😍 $realUrl")
        ContentOpenManager.open(context, item)

        val startedIntent = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals("com.instagram.android", startedIntent.`package`)
    }

    // ── Regression: a stored file whose OCR text contains a URL must open in the
    //    correct in-app viewer, never Chrome (Phase 5C #3 fix). ──
    @Test
    fun testPhotoWithUrlInOcr_opensImageViewerNotBrowser() {
        val item = VaultItem(
            id = UUID.randomUUID().toString(),
            uri = "/data/user/0/com.amar.vault/files/shared_imports/pic.jpg",
            ocrText = "grab it here https://example.com/promo",
            lang = "en",
            itemType = "PHOTO",
            timestamp = System.currentTimeMillis(),
            originalUri = null,
            mimeType = "image/jpeg",
        )
        ContentOpenManager.open(context, item)

        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        // The precise regression: the OCR-derived URL must never become the open
        // target. Previously this launched https://example.com/promo in the browser.
        assertFalse(
            "Photo with a URL in its OCR text must not open that URL as a link",
            started.dataString?.contains("example.com") == true
        )
    }

    // ── Newly covered platforms ──

    @Test
    fun testTikTok_AppInstalled() {
        val url = "https://www.tiktok.com/@user/video/123"
        registerAppHandler(url, "com.zhiliaoapp.musically")
        ContentOpenManager.open(context, createVaultItem(url))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("com.zhiliaoapp.musically", started.`package`)
    }

    @Test
    fun testPinterest_AppInstalled() {
        val url = "https://www.pinterest.com/pin/123"
        registerAppHandler(url, "com.pinterest")
        ContentOpenManager.open(context, createVaultItem(url))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("com.pinterest", started.`package`)
    }

    @Test
    fun testFacebook_AppInstalled() {
        val url = "https://www.facebook.com/watch/?v=123"
        registerAppHandler(url, "com.facebook.katana")
        ContentOpenManager.open(context, createVaultItem(url))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("com.facebook.katana", started.`package`)
    }

    @Test
    fun testX_AppInstalled() {
        val url = "https://x.com/user/status/123"
        registerAppHandler(url, "com.twitter.android")
        ContentOpenManager.open(context, createVaultItem(url))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("com.twitter.android", started.`package`)
    }

    @Test
    fun testThreads_AppInstalled() {
        val url = "https://www.threads.net/@user/post/123"
        registerAppHandler(url, "com.instagram.barcelona")
        ContentOpenManager.open(context, createVaultItem(url))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("com.instagram.barcelona", started.`package`)
    }

    @Test
    fun testTelegram_AppInstalled() {
        val url = "https://t.me/somechannel"
        registerAppHandler(url, "org.telegram.messenger")
        ContentOpenManager.open(context, createVaultItem(url))
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("org.telegram.messenger", started.`package`)
    }

    // ── Local file opens in Amar's in-app viewer (no dead content:// URI) ──

    @Test
    fun testImageFile_OpensInAppImageViewer() {
        // In-app viewers launch from an Activity context in production; use a real
        // Activity here so startActivity() succeeds without FLAG_ACTIVITY_NEW_TASK.
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val path = activity.filesDir.absolutePath + "/shared_imports/photo.jpg"
        val item = VaultItem(
            id = UUID.randomUUID().toString(),
            uri = path,
            ocrText = "",
            lang = "en",
            itemType = "PHOTO",
            timestamp = System.currentTimeMillis(),
            originalUri = null,
            mimeType = "image/jpeg",
        )
        ContentOpenManager.open(activity, item)

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(
            AmarImageViewerActivity::class.java.name,
            started.component?.className,
        )
    }
}
