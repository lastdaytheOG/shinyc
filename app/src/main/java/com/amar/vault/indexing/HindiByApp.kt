package com.amar.vault.indexing

import android.content.Context
import android.content.SharedPreferences

class HindiByApp(private val prefs: SharedPreferences) {

    @Synchronized
    fun shouldRead(app: String): Boolean {
        val seen = prefs.getString(app, "").orEmpty()
        return seen.length < LEARN || HINDI in seen
    }

    @Synchronized
    fun record(app: String, hindi: Boolean) {
        val seen = (prefs.getString(app, "").orEmpty() + if (hindi) HINDI else NONE).takeLast(WINDOW)
        prefs.edit().putString(app, seen).apply()
    }

    companion object {
        const val LEARN = 3
        const val WINDOW = 10
        const val NO_APP = "-"
        private const val HINDI = '1'
        private const val NONE = '0'

        @Volatile
        private var instance: HindiByApp? = null

        fun get(context: Context): HindiByApp = instance ?: synchronized(this) {
            instance ?: HindiByApp(
                context.applicationContext.getSharedPreferences("hindi_by_app", Context.MODE_PRIVATE),
            ).also { instance = it }
        }

        fun appOf(fileName: String): String {
            val stem = fileName.substringAfterLast('/').substringBeforeLast('.')
            if ('_' !in stem) return NO_APP
            val last = stem.substringAfterLast('_')
            return if (last.any { it.isLetter() }) last else NO_APP
        }
    }
}
