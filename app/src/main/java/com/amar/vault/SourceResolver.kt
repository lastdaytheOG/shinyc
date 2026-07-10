package com.amar.vault

import android.net.Uri

object SourceResolver {
    fun getReadableAppName(sourceApp: String?, originalUri: String?): String {
        val app = sourceApp?.trim()?.lowercase().orEmpty()
        val host = runCatching { Uri.parse(originalUri).host?.lowercase() }.getOrNull().orEmpty()

        return when {
            // Instagram
            app == "com.instagram.android" || host.contains("instagram.com") || host.contains("instagr.am") -> "Instagram"
            
            // YouTube
            app == "com.google.android.youtube" || host.contains("youtube.com") || host.contains("youtu.be") -> "YouTube"
            
            // Spotify
            app == "com.spotify.music" || host.contains("spotify.com") -> "Spotify"
            
            // Reddit
            app == "com.reddit.frontpage" || host.contains("reddit.com") || host.contains("redd.it") -> "Reddit"
            
            // Amazon
            app == "com.amazon.mshop.android.shopping" || app == "in.amazon.mshop.android.shopping" || 
            host.contains("amazon.in") || host.contains("amazon.com") || host.contains("amzn.to") -> "Amazon"
            
            // Flipkart
            app == "com.flipkart.android" || host.contains("flipkart.com") || host.contains("fkrt.it") -> "Flipkart"
            
            // Play Store
            app == "com.android.vending" || host.contains("play.google.com") -> "Play Store"
            
            // LinkedIn
            app == "com.linkedin.android" || host.contains("linkedin.com") || host.contains("lnkd.in") -> "LinkedIn"
            
            // Web / Chrome fallbacks
            app == "com.android.chrome" || app == "com.google.android.apps.docs" || host.isNotEmpty() -> {
                val cleanHost = host.removePrefix("www.").substringBefore(".")
                if (cleanHost.isNotEmpty()) cleanHost.replaceFirstChar { it.uppercase() } else "Chrome"
            }
            
            // Device Apps
            app.contains("camera") -> "Camera"
            app.contains("gallery") -> "Gallery"
            app.contains("photos") -> "Gallery"
            
            // Fallback
            app.isNotEmpty() -> {
                val lastDot = app.lastIndexOf('.')
                if (lastDot >= 0 && lastDot < app.length - 1) {
                    app.substring(lastDot + 1).replaceFirstChar { it.uppercase() }
                } else {
                    app.replaceFirstChar { it.uppercase() }
                }
            }
            else -> "Saved"
        }
    }
}
