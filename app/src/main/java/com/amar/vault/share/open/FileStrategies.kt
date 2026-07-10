package com.amar.vault.share.open

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.amar.vault.AmarImageViewerActivity
import com.amar.vault.AmarMediaViewerActivity
import com.amar.vault.AmarReaderActivity
import com.amar.vault.PdfViewerActivity

/**
 * Local-file open strategies. Each is applicable only when [OpenTarget.url] is
 * null (i.e. this is a captured file, not a link). Image/PDF/Video/Audio/Text
 * open in Amar's own in-process viewers; office/unknown files hand off to an
 * external viewer through a FileProvider content:// URI.
 */

private fun OpenTarget.isFile(): Boolean = url == null

class ImageStrategy : OpenStrategy {
    override val name = "Image"
    override fun canHandle(target: OpenTarget): Boolean = target.isFile() && (
        target.mimeType.startsWith("image/") ||
            target.itemType in setOf("photo", "screenshot", "image") ||
            listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic").any { target.pathForExtSniff.endsWith(it) }
        )

    override fun open(context: Context, target: OpenTarget): Boolean {
        IntentLauncher.log(name, "opening in-app image viewer")
        AmarImageViewerActivity.open(context, target.item)
        return true
    }
}

class PdfStrategy : OpenStrategy {
    override val name = "Pdf"
    override fun canHandle(target: OpenTarget): Boolean = target.isFile() && (
        target.itemType == "pdf" || target.mimeType == "application/pdf" || target.pathForExtSniff.endsWith(".pdf")
        )

    override fun open(context: Context, target: OpenTarget): Boolean {
        IntentLauncher.log(name, "opening in-app pdf viewer")
        PdfViewerActivity.open(
            context = context,
            uri = target.localUri ?: Uri.parse(target.item.uri),
            page = 0,
            fileName = target.title ?: target.item.sourceFile,
        )
        return true
    }
}

class VideoStrategy : OpenStrategy {
    override val name = "Video"
    override fun canHandle(target: OpenTarget): Boolean = target.isFile() && (
        target.itemType == "video" || target.mimeType.startsWith("video/") ||
            listOf(".mp4", ".mkv", ".mov", ".webm", ".3gp", ".avi").any { target.pathForExtSniff.endsWith(it) }
        )

    override fun open(context: Context, target: OpenTarget): Boolean {
        IntentLauncher.log(name, "opening in-app video player")
        AmarMediaViewerActivity.open(context, target.item, isVideo = true)
        return true
    }
}

class AudioStrategy : OpenStrategy {
    override val name = "Audio"
    override fun canHandle(target: OpenTarget): Boolean = target.isFile() && (
        target.itemType == "audio" || target.mimeType.startsWith("audio/") ||
            listOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac").any { target.pathForExtSniff.endsWith(it) }
        )

    override fun open(context: Context, target: OpenTarget): Boolean {
        IntentLauncher.log(name, "opening in-app audio player")
        AmarMediaViewerActivity.open(context, target.item, isVideo = false)
        return true
    }
}

class TextStrategy : OpenStrategy {
    override val name = "Text"
    override fun canHandle(target: OpenTarget): Boolean = target.isFile() && (
        target.itemType == "text" || target.mimeType.startsWith("text/") ||
            target.item.uri.startsWith("share://text/") ||
            listOf(".txt", ".md", ".log", ".json", ".csv").any { target.pathForExtSniff.endsWith(it) }
        )

    override fun open(context: Context, target: OpenTarget): Boolean {
        IntentLauncher.log(name, "opening in-app reader")
        AmarReaderActivity.open(context, target.item)
        return true
    }
}

/** Office and other structured documents → external viewer via FileProvider. */
class DocumentStrategy : OpenStrategy {
    override val name = "Document"
    private val officeMimes = setOf(
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
    private val officeExts = listOf(".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx")

    override fun canHandle(target: OpenTarget): Boolean = target.isFile() && (
        target.mimeType in officeMimes || officeExts.any { target.pathForExtSniff.endsWith(it) }
        )

    override fun open(context: Context, target: OpenTarget): Boolean {
        val uri = target.localUri ?: return false
        return ExternalViewer.open(context, name, uri, target.mimeType.ifBlank { "*/*" })
    }
}

/** Last-resort strategy for any captured file: hand to an external viewer. */
class UnknownStrategy : OpenStrategy {
    override val name = "Unknown"
    override fun canHandle(target: OpenTarget): Boolean = true

    override fun open(context: Context, target: OpenTarget): Boolean {
        val uri = target.localUri ?: run {
            IntentLauncher.log(name, "no openable target")
            return false
        }
        return ExternalViewer.open(context, name, uri, target.mimeType.ifBlank { "*/*" })
    }
}

/** Shared helper: view a content:// URI with a specific mime, else offer a chooser. */
internal object ExternalViewer {
    fun open(context: Context, strategy: String, uri: Uri, mime: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (IntentLauncher.canResolve(context, intent)) {
            IntentLauncher.log(strategy, "opened in external viewer ($mime)")
            return IntentLauncher.start(context, intent)
        }
        IntentLauncher.log(strategy, "no direct viewer, offering chooser")
        val chooser = Intent.createChooser(intent, "Open with").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return IntentLauncher.start(context, chooser)
    }
}
