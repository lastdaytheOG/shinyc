package com.amar.vault

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.savedPrefsStore: DataStore<Preferences> by preferencesDataStore(name = "saved_prefs")

/**
 * Smallest-footprint persistence for Saved-only organizational state that isn't a
 * property of an item itself: category display order, pinned categories, the
 * recently-opened trail, and the chosen sort. Backed by Jetpack DataStore so it
 * survives app restarts without any database schema change.
 *
 * (Archived state lives on the item as [StashItem.vaultType] and persists in the DB.)
 */
object SavedPreferences {

    private val KEY_CATEGORY_ORDER = stringPreferencesKey("category_order")     // "\n"-joined
    private val KEY_PINNED = stringSetPreferencesKey("pinned_categories")
    private val KEY_RECENT_OPENED = stringPreferencesKey("recent_opened")       // "\n"-joined, newest first
    private val KEY_RECENT_MOVED = stringPreferencesKey("recent_moved")
    private val KEY_RECENT_FAVORITED = stringPreferencesKey("recent_favorited")
    private val KEY_SORT = stringPreferencesKey("saved_sort")
    private val KEY_FOLDER_META = stringPreferencesKey("folder_meta_json")      // JSON: {name: {...}}
    private val KEY_KNOWN_FOLDERS = stringPreferencesKey("known_folders")       // "\n"-joined, user-created (incl. empty)
    private val KEY_RECENT_SAVED_TO = stringPreferencesKey("recent_saved_to")   // "\n"-joined destination folders, newest first

    private const val SEP = "\n"
    private const val RECENT_CAP = 500

    data class Prefs(
        val categoryOrder: List<String> = emptyList(),
        val pinned: Set<String> = emptySet(),
        val recentlyOpened: List<String> = emptyList(),
        val recentlyMoved: List<String> = emptyList(),
        val recentlyFavorited: List<String> = emptyList(),
        val sort: String = SavedSortOption.NEWEST.name,
        /** Per-folder appearance overrides (theme/accent/icon/description), keyed by name. */
        val folderMeta: Map<String, FolderMeta> = emptyMap(),
        /** User-created folder names, so empty folders still surface in the grid. */
        val knownFolders: List<String> = emptyList(),
        /** Folders most recently saved into (newest first) — surfaces them first in the save sheet. */
        val recentlySavedTo: List<String> = emptyList()
    )

    fun prefsFlow(context: Context): Flow<Prefs> =
        context.savedPrefsStore.data.map { p ->
            Prefs(
                categoryOrder = p[KEY_CATEGORY_ORDER]?.splitList() ?: emptyList(),
                pinned = p[KEY_PINNED] ?: emptySet(),
                recentlyOpened = p[KEY_RECENT_OPENED]?.splitList() ?: emptyList(),
                recentlyMoved = p[KEY_RECENT_MOVED]?.splitList() ?: emptyList(),
                recentlyFavorited = p[KEY_RECENT_FAVORITED]?.splitList() ?: emptyList(),
                sort = p[KEY_SORT] ?: SavedSortOption.NEWEST.name,
                folderMeta = p[KEY_FOLDER_META]?.let { decodeFolderMeta(it) } ?: emptyMap(),
                knownFolders = p[KEY_KNOWN_FOLDERS]?.splitList() ?: emptyList(),
                recentlySavedTo = p[KEY_RECENT_SAVED_TO]?.splitList() ?: emptyList()
            )
        }

    /** Records a save destination at the front of the recently-saved-to trail (deduped, capped). */
    suspend fun recordSavedTo(context: Context, category: String) {
        val clean = category.trim()
        if (clean.isBlank()) return
        recordTrail(context, KEY_RECENT_SAVED_TO, clean)
    }

    // ── Folder appearance + created-folder registry (Phase 5 #3/#4) ───────────

    /** Persist appearance for [name] and register it as a known folder (so an empty one still shows). */
    suspend fun saveFolderMeta(context: Context, name: String, meta: FolderMeta) {
        val clean = name.trim()
        if (clean.isBlank()) return
        context.savedPrefsStore.edit { p ->
            val current = p[KEY_FOLDER_META]?.let { decodeFolderMeta(it) } ?: emptyMap()
            p[KEY_FOLDER_META] = encodeFolderMeta(current + (clean to meta))
            val known = p[KEY_KNOWN_FOLDERS]?.splitList() ?: emptyList()
            if (clean !in known) p[KEY_KNOWN_FOLDERS] = (known + clean).joinList()
        }
    }

    private fun decodeFolderMeta(json: String): Map<String, FolderMeta> = runCatching {
        val obj = org.json.JSONObject(json)
        buildMap {
            obj.keys().forEach { key ->
                val o = obj.getJSONObject(key)
                put(
                    key,
                    FolderMeta(
                        theme = runCatching { FolderTheme.valueOf(o.optString("theme", FolderTheme.COLOR_COVER.name)) }
                            .getOrDefault(FolderTheme.COLOR_COVER),
                        accent = o.optLong("accent", 0xFFAF52DE),
                        icon = o.optString("icon", "📁"),
                        description = o.optString("description", "").ifBlank { null }
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun encodeFolderMeta(map: Map<String, FolderMeta>): String {
        val obj = org.json.JSONObject()
        map.forEach { (name, meta) ->
            obj.put(name, org.json.JSONObject().apply {
                put("theme", meta.theme.name)
                put("accent", meta.accent)
                put("icon", meta.icon)
                meta.description?.let { put("description", it) }
            })
        }
        return obj.toString()
    }

    suspend fun setCategoryOrder(context: Context, order: List<String>) {
        context.savedPrefsStore.edit { it[KEY_CATEGORY_ORDER] = order.joinList() }
    }

    suspend fun togglePin(context: Context, category: String) {
        context.savedPrefsStore.edit { p ->
            val current = p[KEY_PINNED] ?: emptySet()
            p[KEY_PINNED] = if (category in current) current - category else current + category
        }
    }

    suspend fun setSort(context: Context, sort: SavedSortOption) {
        context.savedPrefsStore.edit { it[KEY_SORT] = sort.name }
    }

    /** Records an opened item at the front of the recently-opened trail (deduped, capped). */
    suspend fun recordOpened(context: Context, stashId: String) = recordTrail(context, KEY_RECENT_OPENED, stashId)

    suspend fun recordMoved(context: Context, stashIds: List<String>) =
        stashIds.forEach { recordTrail(context, KEY_RECENT_MOVED, it) }

    suspend fun recordFavorited(context: Context, stashIds: List<String>) =
        stashIds.forEach { recordTrail(context, KEY_RECENT_FAVORITED, it) }

    private suspend fun recordTrail(context: Context, key: androidx.datastore.preferences.core.Preferences.Key<String>, stashId: String) {
        if (stashId.isBlank()) return
        context.savedPrefsStore.edit { p ->
            val current = p[key]?.splitList() ?: emptyList()
            p[key] = (listOf(stashId) + current.filter { it != stashId }).take(RECENT_CAP).joinList()
        }
    }

    /** Keep order/pins/appearance consistent when a category is renamed or merged. */
    suspend fun onCategoryRenamed(context: Context, oldName: String, newName: String) {
        context.savedPrefsStore.edit { p ->
            p[KEY_CATEGORY_ORDER]?.splitList()?.let { order ->
                p[KEY_CATEGORY_ORDER] = order
                    .map { if (it == oldName) newName else it }
                    .distinct()
                    .joinList()
            }
            (p[KEY_PINNED])?.let { pins ->
                if (oldName in pins) p[KEY_PINNED] = (pins - oldName) + newName
            }
            p[KEY_FOLDER_META]?.let { decodeFolderMeta(it) }?.let { meta ->
                meta[oldName]?.let { moved ->
                    p[KEY_FOLDER_META] = encodeFolderMeta((meta - oldName) + (newName to moved))
                }
            }
            p[KEY_KNOWN_FOLDERS]?.splitList()?.let { known ->
                if (oldName in known) {
                    p[KEY_KNOWN_FOLDERS] = (known.map { if (it == oldName) newName else it }).distinct().joinList()
                }
            }
        }
    }

    /** Drop a deleted category from order + pins + appearance + registry. */
    suspend fun onCategoryRemoved(context: Context, name: String) {
        context.savedPrefsStore.edit { p ->
            p[KEY_CATEGORY_ORDER]?.splitList()?.let { order ->
                p[KEY_CATEGORY_ORDER] = order.filter { it != name }.joinList()
            }
            (p[KEY_PINNED])?.let { pins -> p[KEY_PINNED] = pins - name }
            p[KEY_FOLDER_META]?.let { decodeFolderMeta(it) }?.let { meta ->
                if (name in meta) p[KEY_FOLDER_META] = encodeFolderMeta(meta - name)
            }
            p[KEY_KNOWN_FOLDERS]?.splitList()?.let { known ->
                if (name in known) p[KEY_KNOWN_FOLDERS] = known.filter { it != name }.joinList()
            }
        }
    }

    private fun String.splitList(): List<String> =
        if (isEmpty()) emptyList() else split(SEP).filter { it.isNotEmpty() }

    private fun List<String>.joinList(): String = joinToString(SEP)
}
