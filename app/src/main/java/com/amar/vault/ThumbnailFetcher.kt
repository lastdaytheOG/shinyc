package com.amar.vault

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads thumbnails for URL-based shares during background enrichment.
 * Uses og:image meta tag extraction for generic URLs, and direct thumbnail
 * URLs for known platforms (YouTube, etc.).
 *
 * Designed to run on Dispatchers.IO — all methods are blocking.
 */
object ThumbnailFetcher {
    private const val TAG = "ThumbnailFetcher"
    private const val CONNECT_TIMEOUT = 5_000
    private const val READ_TIMEOUT = 8_000
    private const val MAX_IMAGE_SIZE = 2_000_000 // 2MB cap

    /**
     * Attempts to download a thumbnail for the given URI. Returns the local
     * file path on success, or null on failure (never throws).
     */
    fun fetchThumbnail(context: Context, uri: String, stashId: String): String? {
        if (uri.isBlank()) return null

        // Skip local files — they already have their own preview
        if (!uri.startsWith("http://", ignoreCase = true) &&
            !uri.startsWith("https://", ignoreCase = true)
        ) return null

        return try {
            val imageUrl = resolveImageUrl(uri) ?: return null
            downloadToFile(context, imageUrl, stashId)
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail fetch failed for $uri: ${e.message}")
            null
        }
    }

    /**
     * Resolves the best thumbnail URL for a given page URL.
     * Uses platform-specific strategies first, then falls back to og:image.
     */
    private fun resolveImageUrl(pageUrl: String): String? {
        val lower = pageUrl.lowercase()

        // YouTube — direct thumbnail URL (no network call needed)
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            val videoId = extractYouTubeId(pageUrl)
            if (videoId != null) return "https://img.youtube.com/vi/$videoId/mqdefault.jpg"
        }

        // For all other URLs, try og:image extraction
        return extractOgImage(pageUrl)
    }

    /**
     * Extracts og:image from a page's HTML using a lightweight partial download.
     * Only reads the first ~16KB of the page (enough for <head> section).
     */
    private fun extractOgImage(pageUrl: String): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(pageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            connection.setRequestProperty("Accept", "text/html")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            // Read only first 16KB — enough for the <head> section
            val buffer = ByteArray(16384)
            val bytesRead = connection.inputStream.use { it.read(buffer) }
            if (bytesRead <= 0) return null

            val html = String(buffer, 0, bytesRead, Charsets.UTF_8)

            // Extract og:image content
            val ogPatterns = listOf(
                Regex("""<meta[^>]+property\s*=\s*["']og:image["'][^>]+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+property\s*=\s*["']og:image["']""", RegexOption.IGNORE_CASE)
            )

            for (pattern in ogPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val imageUrl = match.groupValues[1].trim()
                    if (imageUrl.startsWith("http")) return imageUrl
                }
            }

            return null
        } catch (e: Exception) {
            Log.d(TAG, "og:image extraction failed for $pageUrl: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Downloads an image URL to local storage. Returns the local file path.
     */
    private fun downloadToFile(context: Context, imageUrl: String, stashId: String): String? {
        val thumbDir = File(context.filesDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()

        val destFile = File(thumbDir, "$stashId.jpg")
        if (destFile.exists()) return destFile.absolutePath // Already downloaded

        var connection: HttpURLConnection? = null
        try {
            connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null

            val contentLength = connection.contentLength
            if (contentLength > MAX_IMAGE_SIZE) {
                Log.d(TAG, "Image too large ($contentLength bytes), skipping")
                return null
            }

            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytes = 0
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        totalBytes += bytesRead
                        if (totalBytes > MAX_IMAGE_SIZE) {
                            destFile.delete()
                            return null
                        }
                        output.write(buffer, 0, bytesRead)
                    }
                }
            }

            return destFile.absolutePath
        } catch (e: Exception) {
            destFile.delete()
            Log.w(TAG, "Image download failed: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractYouTubeId(url: String): String? {
        val patterns = listOf(
            Regex("(?:v=)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE),
            Regex("(?:youtu\\.be/)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE),
            Regex("(?:embed/)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE),
            Regex("(?:shorts/)([a-zA-Z0-9_-]{11})", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            val match = regex.find(url)
            if (match != null) return match.groupValues.getOrNull(1)
        }
        return null
    }
}
