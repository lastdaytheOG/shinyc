package com.amar.vault.indexing

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The password of the PDF being read, carried with the reading the way the profiler is, so that it
 * passes through no signature and no log line. [toString] does not say it.
 */
class PdfPassword(val value: String) : AbstractCoroutineContextElement(PdfPassword) {
    companion object Key : CoroutineContext.Key<PdfPassword>

    override fun toString(): String = "PdfPassword(hidden)"
}

/**
 * Passwords the user typed for locked PDFs, held in memory and nowhere else: not in the database,
 * not in preferences, not in a log. When the app's process ends they are gone, and a locked file
 * that is not finished, or is opened again, asks again. A password being a key to someone's ID or
 * bank statement, that is the better side of the trade.
 */
object PdfPasswords {

    private class Held(val password: String, val typedForThisFile: Boolean)

    private val forReading = ConcurrentHashMap<String, Held>()
    private val forViewing = ConcurrentHashMap<String, String>()

    /** The user typed [password] for the import [id]. */
    fun typed(id: String, password: String) {
        forReading[id] = Held(password, typedForThisFile = true)
    }

    /** [password] opened another file and is worth trying on [id]: statements of one bank share one. */
    fun tryOn(id: String, password: String) {
        forReading[id] = Held(password, typedForThisFile = false)
    }

    fun of(id: String): String? = forReading[id]?.password

    fun wasTypedFor(id: String): Boolean = forReading[id]?.typedForThisFile == true

    fun forget(id: String) {
        forReading.remove(id)
    }

    /**
     * [password] opens the file with this [fingerprint], so the viewer need not ask while the app is
     * running. By what is in the file and not where it is: a shared file reaches the viewer under
     * another address than the one it was read from.
     */
    fun opens(fingerprint: String, password: String) {
        forViewing[fingerprint] = password
    }

    fun forViewing(fingerprint: String): String? = forViewing[fingerprint]

    internal fun clear() {
        forReading.clear()
        forViewing.clear()
    }
}

/**
 * What the viewer shows a locked PDF from. Android's page renderer cannot be given a password before
 * Android 15, so the file is written out with its lock taken off, in the app's private cache, and the
 * caller opens that and deletes it at once ([open]): the open file stays readable and nothing is left
 * lying about, not even if the app is killed while the document is on screen.
 */
internal object LockedPdfCopy {

    private const val FOLDER = "unlocked-pdf"

    /** An unlocked copy to open and delete, or null when [password] does not open the file. */
    fun make(context: Context, uri: Uri, password: String): File? {
        PDFBoxResourceLoader.init(context.applicationContext)
        val folder = File(context.cacheDir, FOLDER).apply { mkdirs() }
        // One left by a crash in the moment between writing it and opening it.
        folder.listFiles()?.forEach { it.delete() }
        val copy = File(folder, UUID.randomUUID().toString())
        return try {
            openDocStream(context, uri).use { stream ->
                PDDocument.load(stream, password).use { document ->
                    document.isAllSecurityToBeRemoved = true
                    document.save(copy)
                }
            }
            copy
        } catch (wrong: InvalidPasswordException) {
            copy.delete()
            null
        } catch (other: Throwable) {
            copy.delete()
            throw other
        }
    }
}
