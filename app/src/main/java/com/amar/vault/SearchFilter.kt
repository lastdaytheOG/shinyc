package com.amar.vault

/**
 * The search screen's filter chips, and what each one lets through.
 *
 * The answer comes from the item itself (and, for the two chips that are about the Stash, from
 * its saved entry), so it can be asked inside the search engine — before the engine's caps, so
 * that images are not crowded out by documents when only images are wanted — and again when a
 * result is drawn.
 */
object SearchFilter {
    const val ALL = "All"
    const val IMAGES = "Images"
    const val VIDEOS = "Videos"
    const val ARTICLES = "Articles"
    const val PRODUCTS = "Products"
    const val MUSIC = "Music"
    const val DOCUMENTS = "Documents"
    const val FAVORITES = "Favorites"
    const val FOLDERS = "Folders"

    val CHIPS = listOf(ALL, IMAGES, VIDEOS, ARTICLES, PRODUCTS, MUSIC, DOCUMENTS, FAVORITES, FOLDERS)

    /**
     * What the indexers call a picture. The gallery scan names it after the folder it was
     * found in (screenshot, camera, whatsapp, photo); a shared picture is a PHOTO or a
     * SCREENSHOT; the developer screen's is dev_manual.
     */
    private val IMAGE_TYPES = setOf("photo", "screenshot", "camera", "whatsapp", "image", "dev_manual")

    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif", ".bmp")

    /**
     * A photo or a screenshot. Its text was read off the picture; that does not make it a note.
     * Told by its type, and for a type not listed above by where it is kept: the gallery, or a
     * picture file on the phone. A link to a picture on the web is a link.
     */
    fun isImage(itemType: String, mimeType: String?, uri: String): Boolean {
        if (itemType.lowercase() in IMAGE_TYPES || mimeType?.startsWith("image/", ignoreCase = true) == true) return true
        val where = uri.trim()
        return (where.startsWith("content://media/", ignoreCase = true) && where.contains("/images/")) ||
            ((where.startsWith("/") || where.startsWith("file://", ignoreCase = true)) &&
                IMAGE_EXTENSIONS.any { where.endsWith(it, ignoreCase = true) })
    }

    fun isImage(item: VaultItem): Boolean = isImage(item.itemType, item.mimeType, item.uri)

    /** A PDF, Word, Excel or EPUB file, or a page of one. */
    fun isDocument(item: VaultItem): Boolean =
        !isImage(item) && (
            ContentSpecies.classify(item) == ContentSpecies.PDF ||
                ContentSpecies.isOfficeDocument(item.itemType, item.mimeType, item.uri)
            )

    /**
     * Whether [chip] lets [item] through. [saved] is the item's Stash entry — or, for a page
     * of a document, the entry of that document — and null when it was never saved.
     */
    fun accepts(chip: String, item: VaultItem, saved: StashItemWithVaultItem?): Boolean = when (chip) {
        ALL -> true
        IMAGES -> isImage(item)
        DOCUMENTS -> isDocument(item)
        FAVORITES -> saved?.isFavorite == true
        FOLDERS -> saved != null && saved.category.isNotBlank()
        // The rest are kinds of saved link. A picture or a document is never one of them,
        // whatever words happen to be printed on it.
        else -> !isImage(item) && !isDocument(item) &&
            when (ContentSpecies.classify(item)) {
                ContentSpecies.YOUTUBE_VIDEO, ContentSpecies.REEL -> chip == VIDEOS
                ContentSpecies.WEBSITE -> chip == ARTICLES
                ContentSpecies.PRODUCT -> chip == PRODUCTS
                ContentSpecies.AUDIO -> chip == MUSIC
                // A video file that was shared, as opposed to a link to one.
                else -> chip == VIDEOS && (item.itemType.equals("VIDEO", ignoreCase = true) ||
                    item.mimeType?.startsWith("video/", ignoreCase = true) == true)
            }
    }
}
