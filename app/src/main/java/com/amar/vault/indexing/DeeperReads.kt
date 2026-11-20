package com.amar.vault.indexing

import android.content.Context
import java.io.File

class DeeperReads(private val file: File) {

    private val ids = LinkedHashSet<String>()
    private var loaded = false

    @Synchronized
    fun mark(id: String, deeper: Boolean): Boolean {
        load()
        val wasEmpty = ids.isEmpty()
        val changed = if (deeper) ids.add(id) else ids.remove(id)
        if (changed) save()
        return deeper && changed && wasEmpty
    }

    @Synchronized
    fun waiting(): List<String> {
        load()
        return ids.toList()
    }

    @Synchronized
    fun size(): Int {
        load()
        return ids.size
    }

    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        file.readLines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { ids += it }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        val next = File(file.path + ".next")
        next.writeText(ids.joinToString("\n"))
        if (!next.renameTo(file)) {
            file.delete()
            next.renameTo(file)
        }
    }

    companion object {
        @Volatile
        private var instance: DeeperReads? = null

        @Volatile
        private var checks: DeeperReads? = null

        fun get(context: Context): DeeperReads = instance ?: synchronized(this) {
            instance ?: DeeperReads(File(context.applicationContext.filesDir, "deeper-reads.txt")).also { instance = it }
        }

        fun hindiChecks(context: Context): DeeperReads = checks ?: synchronized(this) {
            checks ?: DeeperReads(File(context.applicationContext.filesDir, "hindi-checks.txt")).also { checks = it }
        }
    }
}
