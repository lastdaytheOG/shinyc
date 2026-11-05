package com.amar.vault

/**
 * The search screen's filter chips, and what each one lets through.
 *
 * The answer comes from the item itself, so it can be asked inside the search engine — before the
 * engine's caps, so that images are not crowded out by documents when only images are wanted — and
 * again when a result is drawn.
 */
object SearchFilter {
    const val ALL = "All"
    const val IMAGES = "Images"
    const val VIDEOS = "Videos"
    const val ARTICLES = "Articles"
    const val PRODUCTS = "Products"
    const val MUSIC = "Music"
    const val DOCUMENTS = "Documents"

    val CHIPS = listOf(ALL, IMAGES, VIDEOS, ARTICLES, PRODUCTS, MUSIC, DOCUMENTS)

    /**
     * A photo or a screenshot. Its text was read off the picture; that does not make it a note.
     * Its type says so and nothing else is consulted: what is a picture is decided when an
     * item is stored ([PictureKind]), not guessed again each time one is listed.
     */
    fun isImage(item: VaultItem): Boolean = item.itemType.isImage

    /** A PDF, Word, Excel or EPUB file, or a page of one. */
    fun isDocument(item: VaultItem): Boolean =
        !isImage(item) && (
            ContentSpecies.classify(item) == ContentSpecies.PDF ||
                ContentSpecies.isOfficeDocument(item.itemType, item.mimeType, item.uri)
            )

    /** Whether [chip] lets [item] through. */
    fun accepts(chip: String, item: VaultItem): Boolean = when (chip) {
        ALL -> true
        IMAGES -> isImage(item)
        DOCUMENTS -> isDocument(item)
        // The rest are kinds of shared link. A picture or a document is never one of them,
        // whatever words happen to be printed on it.
        else -> !isImage(item) && !isDocument(item) &&
            when (ContentSpecies.classify(item)) {
                ContentSpecies.YOUTUBE_VIDEO, ContentSpecies.REEL -> chip == VIDEOS
                ContentSpecies.WEBSITE -> chip == ARTICLES
                ContentSpecies.PRODUCT -> chip == PRODUCTS
                ContentSpecies.AUDIO -> chip == MUSIC
                // A video file that was shared, as opposed to a link to one.
                else -> chip == VIDEOS && (item.itemType == ItemType.VIDEO ||
                    item.mimeType?.startsWith("video/", ignoreCase = true) == true)
            }
    }
}
