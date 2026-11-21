package com.amar.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.map

private val Context.scanPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "scan_prefs")

/**
 * Persists user's scan preferences — which folders to index, whether
 * onboarding is complete, etc. Backed by Jetpack DataStore.
 */
object ScanPreferences {

    private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    private val KEY_SCAN_ALL = booleanPreferencesKey("scan_all_photos")
    private val KEY_SCAN_SCREENSHOTS = booleanPreferencesKey("scan_screenshots")
    private val KEY_SCAN_CAMERA = booleanPreferencesKey("scan_camera")
    private val KEY_SCAN_WHATSAPP = booleanPreferencesKey("scan_whatsapp")
    private val KEY_SCAN_DOWNLOADS = booleanPreferencesKey("scan_downloads")
    private val KEY_SCAN_DOCUMENTS = booleanPreferencesKey("scan_documents")
    private val KEY_CUSTOM_FOLDERS = stringSetPreferencesKey("custom_folders")
    private val KEY_INITIAL_SCAN_DONE = booleanPreferencesKey("initial_scan_done")
    private val KEY_DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
    private val KEY_APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
    private val KEY_LARGER_TEXT = booleanPreferencesKey("larger_text")
    private val KEY_LANGUAGE = androidx.datastore.preferences.core.stringPreferencesKey("app_language")
    private val KEY_PHOTO_SEARCH_ENABLED = booleanPreferencesKey("photo_search_enabled")

    data class Prefs(
        val onboardingDone: Boolean = false,
        val scanAll: Boolean = false,
        val scanScreenshots: Boolean = true,
        val scanCamera: Boolean = false,
        val scanWhatsApp: Boolean = false,
        val scanDownloads: Boolean = false,
        val scanDocuments: Boolean = true,
        val customFolders: Set<String> = emptySet(),
        val initialScanDone: Boolean = false,
        val developerMode: Boolean = false,
        val appLockEnabled: Boolean = false,
        val largerText: Boolean = false,
        val language: String = "system",
        val photoSearchEnabled: Boolean = false,
    ) {
        /** Returns MediaStore-compatible relative paths to scan. */
        fun foldersToScan(): List<String> {
            if (scanAll) return listOf("") // empty = all
            val folders = mutableListOf<String>()
            if (scanScreenshots) folders.addAll(SCREENSHOT_FOLDERS)
            if (scanCamera) folders.addAll(CAMERA_FOLDERS)
            if (scanWhatsApp) folders.addAll(WHATSAPP_FOLDERS)
            if (scanDownloads) folders.addAll(DOWNLOAD_FOLDERS)
            folders.addAll(customFolders)
            return folders
        }
    }

    val SCREENSHOT_FOLDERS = listOf("Screenshots", "DCIM/Screenshots")
    val CAMERA_FOLDERS = listOf("DCIM/Camera", "Camera")
    val WHATSAPP_FOLDERS = listOf(
        "WhatsApp/Media/WhatsApp Images",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
    )
    val DOWNLOAD_FOLDERS = listOf("Download", "Downloads")

    fun prefsFlow(context: Context): Flow<Prefs> =
        context.scanPrefsStore.data.map { p ->
            Prefs(
                onboardingDone = p[KEY_ONBOARDING_DONE] ?: false,
                scanAll = p[KEY_SCAN_ALL] ?: false,
                scanScreenshots = p[KEY_SCAN_SCREENSHOTS] ?: true,
                scanCamera = p[KEY_SCAN_CAMERA] ?: false,
                scanWhatsApp = p[KEY_SCAN_WHATSAPP] ?: false,
                scanDownloads = p[KEY_SCAN_DOWNLOADS] ?: false,
                scanDocuments = p[KEY_SCAN_DOCUMENTS] ?: true,
                customFolders = p[KEY_CUSTOM_FOLDERS] ?: emptySet(),
                initialScanDone = p[KEY_INITIAL_SCAN_DONE] ?: false,
                developerMode = p[KEY_DEVELOPER_MODE] ?: false,
                appLockEnabled = p[KEY_APP_LOCK_ENABLED] ?: false,
                largerText = p[KEY_LARGER_TEXT] ?: false,
                language = p[KEY_LANGUAGE] ?: "system",
                photoSearchEnabled = p[KEY_PHOTO_SEARCH_ENABLED] ?: false,
            )
        }

    suspend fun save(context: Context, prefs: Prefs) {
        context.scanPrefsStore.edit { p ->
            p[KEY_ONBOARDING_DONE] = prefs.onboardingDone
            p[KEY_SCAN_ALL] = prefs.scanAll
            p[KEY_SCAN_SCREENSHOTS] = prefs.scanScreenshots
            p[KEY_SCAN_CAMERA] = prefs.scanCamera
            p[KEY_SCAN_WHATSAPP] = prefs.scanWhatsApp
            p[KEY_SCAN_DOWNLOADS] = prefs.scanDownloads
            p[KEY_SCAN_DOCUMENTS] = prefs.scanDocuments
            p[KEY_CUSTOM_FOLDERS] = prefs.customFolders
            p[KEY_INITIAL_SCAN_DONE] = prefs.initialScanDone
            p[KEY_DEVELOPER_MODE] = prefs.developerMode
            p[KEY_APP_LOCK_ENABLED] = prefs.appLockEnabled
            p[KEY_LARGER_TEXT] = prefs.largerText
            p[KEY_LANGUAGE] = prefs.language
            p[KEY_PHOTO_SEARCH_ENABLED] = prefs.photoSearchEnabled
        }
    }

    private val changing = kotlinx.coroutines.sync.Mutex()

    suspend fun update(context: Context, change: (Prefs) -> Prefs) {
        changing.withLock {
            val current = prefsFlow(context).first()
            val next = change(current)
            if (next != current) save(context, next)
        }
    }

    suspend fun setDeveloperMode(context: Context, enabled: Boolean) {
        context.scanPrefsStore.edit { it[KEY_DEVELOPER_MODE] = enabled }
    }

    suspend fun markOnboardingDone(context: Context) {
        context.scanPrefsStore.edit { it[KEY_ONBOARDING_DONE] = true }
    }

    suspend fun markInitialScanDone(context: Context) {
        context.scanPrefsStore.edit { it[KEY_INITIAL_SCAN_DONE] = true }
    }

    suspend fun setAppLock(context: Context, enabled: Boolean) {
        context.scanPrefsStore.edit { it[KEY_APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setLargerText(context: Context, enabled: Boolean) {
        context.scanPrefsStore.edit { it[KEY_LARGER_TEXT] = enabled }
    }

    suspend fun setLanguage(context: Context, lang: String) {
        context.scanPrefsStore.edit { it[KEY_LANGUAGE] = lang }
    }

    suspend fun setPhotoSearchEnabled(context: Context, enabled: Boolean) {
        context.scanPrefsStore.edit { it[KEY_PHOTO_SEARCH_ENABLED] = enabled }
    }
}