package com.amar.vault

/**
 * What is known of a picture from where it is kept, before anything is read off it: what its
 * file is called and the folder it is in ("Pictures/Screenshots/", "DCIM/Camera/"). Either
 * may be empty when it is not known.
 */
data class PictureFacts(val name: String, val folder: String = "")

/**
 * Whether a picture is a screenshot or a photo. This is the one place that says.
 *
 * Seven places index pictures, and each used to say for itself: the bulk scan called every
 * picture a photo, the nightly and foreground scans called every picture a screenshot, the
 * folder watcher went by the folder, the developer screen had a name of its own. What a
 * picture was called depended on which of them got to it first.
 *
 * A picture is a screenshot when its file or its folder is named as one — which is how every
 * phone names the screenshots it takes. Anything else is a photo: a camera picture, one that
 * was received or downloaded. The picture itself is not looked at: a screenshot that was
 * forwarded and renamed on the way is a photo here, and nothing is guessed from its shape.
 *
 * Bump [VERSION] when the rule changes: stored pictures are looked at again the next time the
 * app starts ([com.amar.vault.indexing.PictureTypeUpkeep]).
 */
object PictureKind {

    const val VERSION = 1

    /** "Screenshot_2026…", "screen_shot", "Screen Shot 2026-10-05", a "Screenshots" folder. */
    private val SCREENSHOT = Regex("screen[ _-]?shot", RegexOption.IGNORE_CASE)

    fun of(facts: PictureFacts): ItemType =
        if (SCREENSHOT.containsMatchIn(facts.name) || SCREENSHOT.containsMatchIn(facts.folder)) ItemType.SCREENSHOT
        else ItemType.PHOTO

    fun of(name: String, folder: String = ""): ItemType = of(PictureFacts(name, folder))

    /** File-name endings of pictures, lower case with the dot. */
    val FILE_ENDINGS = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif", ".bmp")

    /**
     * Whether what is stored at [uri] with this [mimeType] is a picture at all: it is typed as
     * one, it is one of the gallery's pictures, or it is a picture file kept on the phone. A
     * link to a picture on the web is a link.
     */
    fun isPicture(mimeType: String?, uri: String, fileName: String = ""): Boolean {
        if (mimeType?.startsWith("image/", ignoreCase = true) == true) return true
        val where = uri.trim()
        if (where.startsWith("content://media/", ignoreCase = true) && where.contains("/images/")) return true
        val local = where.startsWith("/") || where.startsWith("file://", ignoreCase = true)
        return local && FILE_ENDINGS.any { where.endsWith(it, ignoreCase = true) || fileName.trim().endsWith(it, ignoreCase = true) }
    }
}
