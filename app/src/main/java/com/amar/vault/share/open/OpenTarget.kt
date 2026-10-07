package com.amar.vault.share.open

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.amar.vault.ItemType
import com.amar.vault.ShareUrlExtractor
import com.amar.vault.VaultItem
import java.io.File

/**
 * A normalized, strategy-agnostic description of "the thing the user wants to
 * open". Built once by [OpenTarget.from] and handed to every [OpenStrategy] so
 * that each strategy only inspects clean fields instead of re-parsing raw
 * VaultItem state.
 *
 * Part of the Share / Saved open subsystem. Introduces no state and touches no
 * unrelated module.
 */
data class OpenTarget(
    /** The backing item — file strategies pass this straight to the in-app viewers. */
    val item: VaultItem,
    /** First openable URL (http/https/market/spotify/intent) if this is a link, else null. */
    val url: String?,
    /** A readable content:// (or file://) URI for a locally stored file, else null. */
    val localUri: Uri?,
    /** Resolved lowercase MIME type (may be blank). */
    val mimeType: String,
    val itemType: ItemType,
    /** Best available human-facing title. */
    val title: String?,
) {
    val host: String get() = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
    val scheme: String get() = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull().orEmpty()

    /** Lowercase path segment used for extension sniffing. */
    val pathForExtSniff: String
        get() = (localUri?.toString() ?: item.uri).substringBefore('?').lowercase()

    companion object {
        // Item types + URI shapes that denote a locally stored file rather than a link.
        private val FILE_ITEM_TYPES = setOf(
            ItemType.PHOTO, ItemType.SCREENSHOT, ItemType.VIDEO, ItemType.AUDIO, ItemType.PDF, ItemType.FILE,
        )

        fun from(context: Context, item: VaultItem): OpenTarget {
            val primaryText = item.originalUri?.takeIf { it.isNotBlank() } ?: item.uri
            // A stored file must never be reclassified as a link just because its OCR
            // text happens to contain a URL — that used to open a photo/PDF/screenshot
            // in Chrome instead of the correct in-app viewer. Only genuine link/text
            // captures (originalUri or an http uri) fall back to OCR-derived URLs.
            val looksLikeLocalFile = item.originalUri.isNullOrBlank() && (
                item.itemType in FILE_ITEM_TYPES ||
                    item.uri.startsWith("/") ||
                    item.uri.startsWith("file:", ignoreCase = true) ||
                    item.uri.startsWith("content:", ignoreCase = true)
                )
            // A link if any candidate field contains a URL.
            val url = ShareUrlExtractor.extractFirstUrl(primaryText)
                ?: if (looksLikeLocalFile) null else ShareUrlExtractor.extractFirstUrl(item.ocrText)

            val localUri = if (url == null) {
                ShareContentUri.resolve(context, item.uri)
            } else {
                null
            }

            return OpenTarget(
                item = item,
                url = url,
                localUri = localUri,
                mimeType = resolveMimeType(context, item),
                itemType = item.itemType,
                title = item.title ?: item.sourceFile,
            )
        }

        private fun resolveMimeType(context: Context, item: VaultItem): String {
            item.mimeType?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
            runCatching { context.contentResolver.getType(Uri.parse(item.uri)) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
            val ext = item.uri.substringBefore('?').substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.lowercase().orEmpty()
        }
    }
}

/**
 * Converts a stored path/URI into a URI that *external* apps can read.
 *
 * Stage-1 capture copies every shared file into the app's private
 * `filesDir/shared_imports`, so the stored value is a bare absolute path. A bare
 * path is not readable by any other app; it must be exposed through FileProvider.
 * This is the fix for "some files cannot reopen / cannot be shared out".
 */
object ShareContentUri {

    /**
     * @return a readable [Uri] for [rawUri], or null if it is blank.
     *  - `content://` is returned unchanged (already grantable).
     *  - `file://` and bare paths are wrapped via FileProvider when the file lives
     *     under a configured path; otherwise the original file URI is returned so
     *     in-process viewers can still read it.
     */
    fun resolve(context: Context, rawUri: String?): Uri? {
        if (rawUri.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "content" -> uri
            "http", "https", "market", "spotify", "intent", "android-app" -> uri
            "file" -> wrap(context, uri.path) ?: uri
            null -> wrap(context, rawUri) ?: uri // bare filesystem path
            else -> uri
        }
    }

    private fun wrap(context: Context, path: String?): Uri? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }.getOrNull()
    }
}
