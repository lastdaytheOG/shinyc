package com.amar.vault

class ContentPriorityResolver {

    fun resolve(attachments: List<IngestionAttachment>): ContentResolution {
        if (attachments.isEmpty()) {
            throw IllegalArgumentException("Cannot resolve empty attachments list")
        }

        // Score attachments
        val scored = attachments.map { attachment ->
            val score = calculateScore(attachment)
            Pair(attachment, score)
        }.sortedByDescending { it.second }

        val primary = scored.first().first
        val secondary = scored.drop(1).map { it.first }

        val openStrategy = determineOpenStrategy(primary)
        val previewType = determinePreviewType(primary)

        return ContentResolution(
            primaryAttachment = primary,
            secondaryAttachments = secondary,
            previewType = previewType,
            recommendedOpenStrategy = openStrategy,
            previewTitle = primary.previewTitle ?: primary.filename ?: primary.domain,
            previewThumbnailPath = primary.thumbnailPath
        )
    }

    private fun calculateScore(attachment: IngestionAttachment): Int {
        val mime = attachment.mimeType.lowercase()
        val uri = (ShareUrlExtractor.extractFirstUrl(attachment.originalUri) ?: attachment.originalUri.orEmpty()).lowercase()
        val type = attachment.attachmentType

        if (type == "TEXT" && ShareUrlExtractor.containsUrl(attachment.originalUri)) {
            if (uri.contains("play.google.com")) return 100
            if (uri.contains("youtube.com") || uri.contains("youtu.be")) return 95
            if (uri.contains("instagram.com")) return 95
            if (uri.contains("amazon.")) return 90
            return 85 // generic link
        }

        if (mime.contains("pdf")) return 85
        if (mime.startsWith("image/")) return 80
        if (mime.startsWith("video/")) return 80
        if (mime.startsWith("audio/")) return 70

        if (type == "TEXT") return 60
        return 50 // unknown file
    }

    private fun determineOpenStrategy(attachment: IngestionAttachment): OpenStrategy {
        val mime = attachment.mimeType.lowercase()
        val uri = (ShareUrlExtractor.extractFirstUrl(attachment.originalUri) ?: attachment.originalUri.orEmpty()).lowercase()
        val type = attachment.attachmentType

        if (type == "TEXT" && ShareUrlExtractor.containsUrl(attachment.originalUri)) {
            if (uri.contains("play.google.com")) return OpenStrategy.OPEN_PLAY_STORE
            if (uri.contains("youtube.com") || uri.contains("youtu.be")) return OpenStrategy.OPEN_YOUTUBE
            if (uri.contains("instagram.com")) return OpenStrategy.OPEN_INSTAGRAM
            return OpenStrategy.OPEN_WEB
        }

        if (mime.contains("pdf")) return OpenStrategy.OPEN_PDF
        if (mime.startsWith("image/")) return OpenStrategy.OPEN_IMAGE
        if (mime.startsWith("video/")) return OpenStrategy.OPEN_VIDEO
        if (mime.startsWith("audio/")) return OpenStrategy.OPEN_AUDIO

        if (type == "TEXT") return OpenStrategy.OPEN_FILE // Or OPEN_TEXT if added

        return OpenStrategy.OPEN_NATIVE_FALLBACK
    }

    /**
     * What the saved item is. A link is a link whichever site it points to ([LinkSite] tells
     * the site from the address when it is shown).
     */
    private fun determinePreviewType(attachment: IngestionAttachment): ItemType {
        val mime = attachment.mimeType.lowercase()
        val type = attachment.attachmentType

        if (type == "TEXT" && ShareUrlExtractor.containsUrl(attachment.originalUri)) return ItemType.LINK

        if (mime.contains("pdf")) return ItemType.PDF
        if (mime.startsWith("image/")) {
            return if (attachment.filename?.lowercase()?.contains("screenshot") == true) ItemType.SCREENSHOT else ItemType.PHOTO
        }
        if (mime.startsWith("video/")) return ItemType.VIDEO
        if (mime.startsWith("audio/")) return ItemType.AUDIO

        if (type == "TEXT") return ItemType.TEXT
        // Any other file: a Word, Excel or EPUB file by its type or its name, else a plain file.
        return ItemType.fromFormer(
            "document", attachment.mimeType,
            uri = attachment.localPath ?: attachment.originalUri.orEmpty(), sourceFile = attachment.filename.orEmpty(),
        )
    }
}
