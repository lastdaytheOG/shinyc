package com.amar.vault.share.open

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * The single entry point for opening captured content. Holds an ordered list of
 * [OpenStrategy] and delegates to the first applicable one. Platform coverage is
 * data-driven via [appLinkPlatforms] — adding Instagram/TikTok/etc. is one row,
 * satisfying "adding a platform = adding a strategy, not editing an if/else".
 */
class OpenStrategyResolver private constructor(
    private val strategies: List<OpenStrategy>,
) {
    private val tag = "AmarOpen"

    fun open(context: Context, target: OpenTarget) {
        for (strategy in strategies) {
            val applicable = runCatching { strategy.canHandle(target) }.getOrDefault(false)
            if (!applicable) continue
            Log.i(tag, "resolver selected=${strategy.name} url=${target.url} mime=${target.mimeType} type=${target.itemType.stored}")
            // A throwing strategy (e.g. a viewer that rejects the context) must never
            // crash the caller — treat it as declined and fall through to the next.
            val opened = runCatching { strategy.open(context, target) }
                .onFailure { Log.w(tag, "strategy=${strategy.name} threw: ${it.message}") }
                .getOrDefault(false)
            if (opened) return
            Log.w(tag, "strategy=${strategy.name} declined, trying next")
        }
        Log.e(tag, "no strategy could open target url=${target.url} uri=${target.item.uri} mime=${target.mimeType}")
        runCatching { Toast.makeText(context, "No app available to open this item", Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /**
         * Platforms that deep-link into a dedicated app when installed and fall
         * back to the browser otherwise. Extend this list to add coverage.
         */
        private data class Platform(
            val name: String,
            val packages: List<String>,
            val hosts: List<String>,
        )

        private val appLinkPlatforms = listOf(
            Platform("Instagram", listOf("com.instagram.android"), listOf("instagram.com", "instagr.am")),
            Platform("Facebook", listOf("com.facebook.katana"), listOf("facebook.com", "fb.com", "fb.watch", "m.facebook.com")),
            Platform("Threads", listOf("com.instagram.barcelona"), listOf("threads.net", "threads.com")),
            Platform("X", listOf("com.twitter.android"), listOf("twitter.com", "x.com", "t.co")),
            Platform("Reddit", listOf("com.reddit.frontpage"), listOf("reddit.com", "redd.it")),
            Platform("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), listOf("tiktok.com", "vm.tiktok.com")),
            Platform("LinkedIn", listOf("com.linkedin.android"), listOf("linkedin.com", "lnkd.in")),
            Platform("Pinterest", listOf("com.pinterest"), listOf("pinterest.com", "pin.it")),
            Platform("Amazon", listOf("com.amazon.mShop.android.shopping", "in.amazon.mShop.android.shopping"), listOf("amazon.", "amzn.to", "a.co")),
            Platform("Flipkart", listOf("com.flipkart.android"), listOf("flipkart.com", "fkrt.it", "dl.flipkart.com")),
            Platform("Telegram", listOf("org.telegram.messenger"), listOf("t.me", "telegram.me")),
            Platform("WhatsApp", listOf("com.whatsapp"), listOf("wa.me", "chat.whatsapp.com")),
        )

        private fun matcher(hosts: List<String>): (String) -> Boolean = { host ->
            hosts.any { h ->
                if (h.endsWith(".")) host.contains(h) // e.g. "amazon." matches amazon.co.uk
                else host == h || host.endsWith(".$h")
            }
        }

        fun default(): OpenStrategyResolver {
            val web = listOf<OpenStrategy>(
                IntentUriStrategy(),
                PlayStoreStrategy(),
                YouTubeStrategy(),
                SpotifyStrategy(),
            )
            val platforms = appLinkPlatforms.map { p ->
                AppLinkStrategy(p.name, p.packages, matcher(p.hosts))
            }
            val browser = listOf<OpenStrategy>(BrowserStrategy())
            val files = listOf<OpenStrategy>(
                ImageStrategy(),
                PdfStrategy(),
                VideoStrategy(),
                AudioStrategy(),
                TextStrategy(),
                DocumentStrategy(),
                UnknownStrategy(),
            )
            return OpenStrategyResolver(web + platforms + browser + files)
        }
    }
}
