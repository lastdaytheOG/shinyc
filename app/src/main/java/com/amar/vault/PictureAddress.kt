package com.amar.vault

import java.net.URLDecoder

object PictureAddress {

    private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
    private const val STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
    private const val DOWNLOAD_DOCUMENTS = "com.android.providers.downloads.documents"
    private const val GALLERY = "content://media/external/images/media/"

    fun candidates(stored: String, deviceStorage: String): List<String> {
        val address = stored.trim()
        if (address.isEmpty()) return emptyList()
        val found = mutableListOf(address)
        if (!address.startsWith("content://", ignoreCase = true)) return found
        val rest = address.substring("content://".length).substringBefore('?')
        val authority = rest.substringBefore('/').lowercase()
        val marker = "/document/"
        val at = rest.lastIndexOf(marker)
        if (at < 0) return found
        val documentId = decode(rest.substring(at + marker.length))
        val kind = documentId.substringBefore(':', "")
        val value = documentId.substringAfter(':', "")
        when (authority) {
            MEDIA_DOCUMENTS ->
                if (kind == "image") value.toLongOrNull()?.let { found += GALLERY + it }
            DOWNLOAD_DOCUMENTS -> when (kind) {
                "raw" -> if (value.startsWith("/")) found += value
                "msf" -> value.toLongOrNull()?.let { found += GALLERY + it }
            }
            STORAGE_DOCUMENTS -> if (value.isNotBlank() && ".." !in value) {
                found += if (kind == "primary") deviceStorage.trimEnd('/') + "/" + value
                else "/storage/$kind/$value"
            }
        }
        return found.distinct()
    }

    private fun decode(text: String): String =
        runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)
}
