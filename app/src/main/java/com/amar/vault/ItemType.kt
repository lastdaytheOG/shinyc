package com.amar.vault

import androidx.room.TypeConverter

/**
 * What a stored item is. This is the whole list: `vault_items.itemType` holds [stored] and
 * nothing else, and code asks the type, never a string.
 *
 * It says how the item is kept and read, not where it came from or what it shows. Which site
 * a [LINK] points to is told from its address ([LinkSite]); how a card is drawn is
 * [ContentSpecies].
 */
enum class ItemType(val stored: String) {
    /** A PDF: the saved file itself, and each stored piece of its text. */
    PDF("pdf"),
    /** A Word file (.docx), likewise. */
    WORD("word"),
    /** An Excel file (.xlsx), likewise. */
    EXCEL("excel"),
    /** An EPUB book, likewise. */
    EPUB("epub"),
    /** A picture of a screen. */
    SCREENSHOT("screenshot"),
    /** Any other picture: from the camera, received, downloaded. */
    PHOTO("photo"),
    VIDEO("video"),
    AUDIO("audio"),
    /** A saved web address. */
    LINK("link"),
    /** Text that was shared in as text. */
    TEXT("text"),
    /** A saved file of a kind the app does not read. */
    FILE("file");

    /** A file whose text the app reads and stores in pieces: [PDF], [WORD], [EXCEL], [EPUB]. */
    val isDocument: Boolean get() = this == PDF || this == WORD || this == EXCEL || this == EPUB

    val isImage: Boolean get() = this == SCREENSHOT || this == PHOTO

    companion object {
        private val BY_STORED = entries.associateBy { it.stored }

        /** The type stored as [stored]; null when it is not one of the names above. */
        fun ofStored(stored: String): ItemType? = BY_STORED[stored]

        /**
         * What an item was called before there was one list, where that name alone says what
         * it is. Each of the seven places that index a picture named it its own way — after
         * the folder it was found in, or the screen that indexed it — and a saved link was
         * named after its site.
         */
        private val FORMER_NAMES: Map<String, ItemType> = mapOf(
            "camera" to PHOTO, "whatsapp" to PHOTO, "dev_manual" to PHOTO, "image" to PHOTO,
            "youtube" to LINK, "reddit" to LINK, "article" to LINK,
        )

        /** How a screenshot's file is told: its name says so. */
        private fun isNamedScreenshot(sourceFile: String) = sourceFile.contains("screenshot", ignoreCase = true)

        /**
         * The type of an item stored under a former name: one of today's names in either case
         * ("PDF", "Photo"), or one of [FORMER_NAMES].
         *
         * [mimeType], [uri] and [sourceFile] decide where the name does not. A shared file was
         * a "DOCUMENT" whatever it was, and a shared picture an "IMAGE" whether or not it was
         * a screenshot. A name that says nothing is read from the same three; with nothing to
         * go by, the item is a [FILE].
         */
        fun fromFormer(
            name: String, mimeType: String? = null, uri: String = "", sourceFile: String = "",
        ): ItemType {
            val lowered = name.trim().lowercase()
            if (lowered == "image" && isNamedScreenshot(sourceFile)) return SCREENSHOT
            BY_STORED[lowered]?.let { return it }
            FORMER_NAMES[lowered]?.let { return it }

            val mime = mimeType?.trim()?.lowercase().orEmpty()
            val where = uri.trim().lowercase()
            val paths = listOf(where.substringBefore('?').substringBefore('#'), sourceFile.trim().lowercase())
            fun named(vararg extensions: String) = paths.any { path -> extensions.any { path.endsWith(it) } }
            return when {
                mime == "application/pdf" || named(".pdf") -> PDF
                "wordprocessingml" in mime || named(".docx") -> WORD
                "spreadsheetml" in mime || named(".xlsx") -> EXCEL
                "epub" in mime || named(".epub") -> EPUB
                mime.startsWith("image/") || (where.startsWith("content://media/") && "/images/" in where) ->
                    if (isNamedScreenshot(sourceFile)) SCREENSHOT else PHOTO
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                where.startsWith("http://") || where.startsWith("https://") -> LINK
                mime.startsWith("text/") || where.startsWith("share://text/") -> TEXT
                else -> FILE
            }
        }
    }
}

/**
 * How Room keeps an [ItemType]. A row holds a name from the list (the version-14 migration
 * rewrote every former name), so anything else was not written by this app; it is read the way
 * a former name is rather than crashing the screen that happened to load it.
 */
class ItemTypeConverter {
    @TypeConverter
    fun toStored(type: ItemType): String = type.stored

    @TypeConverter
    fun fromStored(stored: String): ItemType = ItemType.ofStored(stored) ?: ItemType.fromFormer(stored)
}

/** The site a saved [ItemType.LINK] points to, where the app names a site when it shows one. */
enum class LinkSite {
    YOUTUBE, REDDIT, OTHER;

    companion object {
        fun of(uri: String): LinkSite {
            val address = uri.lowercase()
            return when {
                "youtube.com" in address || "youtu.be" in address -> YOUTUBE
                "reddit.com" in address -> REDDIT
                else -> OTHER
            }
        }
    }
}

/** The small picture a plain list row shows for an item of this type. */
val ItemType.rowIcon: String
    get() = when (this) {
        ItemType.PDF -> "📄"
        ItemType.WORD -> "📝"
        ItemType.EXCEL -> "📊"
        ItemType.EPUB -> "📖"
        ItemType.SCREENSHOT -> "📸"
        else -> "🖼"
    }
