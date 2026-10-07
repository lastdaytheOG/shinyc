package com.amar.vault.indexing

import java.util.concurrent.ConcurrentHashMap

/**
 * What is being read right now, so that the same picture is not read twice at once.
 *
 * Two watchers hear of a new picture in the gallery at the same moment and each hands it to
 * be indexed. The second found the first's row with no text yet — the first was still
 * reading — took it for a row left unfinished, and read the picture again: every new
 * screenshot cost two readings.
 */
class BeingRead {

    private val keys = ConcurrentHashMap.newKeySet<String>()

    /**
     * Runs [read] unless something with this [key] is being read already. False when it was
     * left to the one already reading.
     */
    suspend fun once(key: String, read: suspend () -> Unit): Boolean {
        if (!keys.add(key)) return false
        try {
            read()
        } finally {
            keys.remove(key)
        }
        return true
    }
}
