package com.amar.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
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
        /** Opt-in Developer Mode. Only unlocks the internal Developer Tools suite; never
         *  changes production indexing/retrieval/AI behaviour. See [com.amar.vault.dev.DeveloperMode]. */
        val developerMode: Boolean = false,
    ) {
        /** Returns MediaStore-compatible relative paths to scan. */
        fun foldersToScan(): List<String> {
            if (scanAll) return listOf("") // empty = all
            val folders = mutableListOf<String>()
            if (scanScreenshots) { folders.add("Screenshots"); folders.add("DCIM/Screenshots") }
            if (scanCamera) { folders.add("DCIM/Camera"); folders.add("Camera") }
            if (scanWhatsApp) {
                folders.add("WhatsApp/Media/WhatsApp Images")
                folders.add("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images")
            }
            if (scanDownloads) { folders.add("Download"); folders.add("Downloads") }
            folders.addAll(customFolders)
            return folders
        }
    }

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
}