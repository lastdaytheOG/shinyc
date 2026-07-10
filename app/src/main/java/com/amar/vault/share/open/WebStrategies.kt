package com.amar.vault.share.open

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Link-oriented open strategies. Each is applicable only when [OpenTarget.url] is
 * non-null. They implement the "app first, browser fallback, never a chooser"
 * requirement — every strategy resolves to a concrete package before launching.
 */

/** Handles `intent://` and `android-app://` deep links, honoring browser_fallback_url. */
class IntentUriStrategy : OpenStrategy {
    override val name = "IntentUri"

    override fun canHandle(target: OpenTarget): Boolean {
        val u = target.url?.lowercase() ?: return false
        return u.startsWith("intent://") || u.startsWith("android-app://")
    }

    override fun open(context: Context, target: OpenTarget): Boolean {
        val url = target.url ?: return false
        val parsed = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return false
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        if (IntentLauncher.canResolve(context, parsed) && IntentLauncher.start(context, parsed)) {
            IntentLauncher.log(name, "opened deep link")
            return true
        }
        val fallback = parsed.getStringExtra("browser_fallback_url")
        if (!fallback.isNullOrBlank()) {
            IntentLauncher.log(name, "using browser_fallback_url")
            return BrowserStrategy().open(context, target.copy(url = fallback))
        }
        return false
    }
}

/** Play Store: `market://` or play.google.com → Play Store app, else browser. */
class PlayStoreStrategy : OpenStrategy {
    override val name = "PlayStore"
    private val pkg = "com.android.vending"

    override fun canHandle(target: OpenTarget): Boolean {
        if (target.url == null) return false
        return target.scheme == "market" || target.host == "play.google.com" || target.host.endsWith(".play.google.com")
    }

    override fun open(context: Context, target: OpenTarget): Boolean {
        val uri = Uri.parse(target.url)
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(pkg) }
        if (IntentLauncher.canResolve(context, appIntent) && IntentLauncher.start(context, appIntent)) {
            IntentLauncher.log(name, "opened in Play Store app")
            return true
        }
        val fallback = if (uri.scheme == "market") {
            val query = uri.query ?: uri.schemeSpecificPart.substringAfter("?", "")
            Uri.parse("https://play.google.com/store/apps/details?$query")
        } else uri
        IntentLauncher.log(name, "falling back to browser")
        return BrowserStrategy().openUri(context, fallback)
    }
}

/** YouTube: prefer the app via vnd.youtube:<id>, then app package, then browser. */
class YouTubeStrategy : OpenStrategy {
    override val name = "YouTube"
    private val pkg = "com.google.android.youtube"

    override fun canHandle(target: OpenTarget): Boolean {
        if (target.url == null) return false
        return target.host == "youtube.com" || target.host.endsWith(".youtube.com") ||
            target.host == "youtu.be" || target.host.endsWith(".youtu.be")
    }

    override fun open(context: Context, target: OpenTarget): Boolean {
        val url = target.url ?: return false
        val videoId = extractVideoId(url)
        if (videoId != null) {
            val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
            if (IntentLauncher.canResolve(context, appIntent) && IntentLauncher.start(context, appIntent)) {
                IntentLauncher.log(name, "opened video $videoId in app")
                return true
            }
        }
        return AppLinkStrategy.appOrBrowser(context, name, Uri.parse(url), listOf(pkg))
    }

    private fun extractVideoId(url: String): String? {
        val pattern = "^(?:https?:\\/\\/)?(?:www\\.)?(?:youtube\\.com\\/(?:[^\\/\\n\\s]+\\/\\S+\\/|(?:v|e(?:mbed)?)\\/|\\S*?[?&]v=)|youtu\\.be\\/)([a-zA-Z0-9_-]{11})"
        return Regex(pattern, RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1)
    }
}

/** Spotify: `spotify:` scheme or open.spotify.com → app, else web player. */
class SpotifyStrategy : OpenStrategy {
    override val name = "Spotify"
    private val pkg = "com.spotify.music"

    override fun canHandle(target: OpenTarget): Boolean {
        if (target.url == null) return false
        return target.scheme == "spotify" || target.host == "spotify.com" || target.host.endsWith(".spotify.com")
    }

    override fun open(context: Context, target: OpenTarget): Boolean {
        val uri = Uri.parse(target.url)
        val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(pkg) }
        if (IntentLauncher.canResolve(context, appIntent) && IntentLauncher.start(context, appIntent)) {
            IntentLauncher.log(name, "opened in Spotify app")
            return true
        }
        val fallback = if (uri.scheme == "spotify") {
            val parts = uri.schemeSpecificPart.split(":")
            if (parts.size >= 2) Uri.parse("https://open.spotify.com/${parts[0]}/${parts[1]}") else uri
        } else uri
        IntentLauncher.log(name, "falling back to web player")
        return BrowserStrategy().openUri(context, fallback)
    }
}

/**
 * Generic "open in this app, else browser" strategy, one instance per platform.
 * This is the extension point: supporting a new social/shopping platform is a
 * single [Platform] row in [OpenStrategyResolver], not a new code branch.
 */
class AppLinkStrategy(
    override val name: String,
    private val packages: List<String>,
    private val hostMatch: (String) -> Boolean,
) : OpenStrategy {

    override fun canHandle(target: OpenTarget): Boolean {
        if (target.url == null || target.scheme.let { it != "http" && it != "https" }) return false
        return hostMatch(target.host)
    }

    override fun open(context: Context, target: OpenTarget): Boolean {
        return appOrBrowser(context, name, Uri.parse(target.url), packages)
    }

    companion object {
        fun appOrBrowser(context: Context, name: String, uri: Uri, packages: List<String>): Boolean {
            for (pkg in packages) {
                val appIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(pkg) }
                if (IntentLauncher.canResolve(context, appIntent) && IntentLauncher.start(context, appIntent)) {
                    IntentLauncher.log(name, "opened in $pkg")
                    return true
                }
            }
            IntentLauncher.log(name, "app unavailable, using browser")
            return BrowserStrategy().openUri(context, uri)
        }
    }
}

/** Terminal web fallback: opens any http(s)/market/etc URL in the user's browser. */
class BrowserStrategy : OpenStrategy {
    override val name = "Browser"

    override fun canHandle(target: OpenTarget): Boolean = target.url != null

    override fun open(context: Context, target: OpenTarget): Boolean =
        openUri(context, Uri.parse(target.url))

    fun openUri(context: Context, uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (!IntentLauncher.canResolve(context, intent)) {
            IntentLauncher.log(name, "no browser available")
            return false
        }
        IntentLauncher.log(name, "opened in browser")
        return IntentLauncher.start(context, intent)
    }
}
