package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs

data class Editor(
    val editor : SharedPreferences.Editor
) {
    fun<T> setKeyRaw(path: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        if (isStringSet(value)) {
            editor.putStringSet(path, value as Set<String>)
        } else {
            when (value) {
                is Boolean -> editor.putBoolean(path, value)
                is Int -> editor.putInt(path, value)
                is String -> editor.putString(path, value)
                is Float -> editor.putFloat(path, value)
                is Long -> editor.putLong(path, value)
            }
        }
    }

    private fun isStringSet(value: Any?) : Boolean {
        if (value is Set<*>) {
            return value.filterIsInstance<String>().size == value.size
        }
        return false
    }

    fun apply() {
        editor.apply()
        System.gc()
    }
}

object BackupUtils {
    val nonTransferableKeys = listOf(
        "anilist_unixtime",
        "anilist_token",
        "anilist_user",
        "anilist_cached_list",
        "anilist_accounts",
        "anilist_active",
        "mal_user",
        "mal_cached_list",
        "mal_unixtime",
        "mal_refresh_token",
        "mal_token",
        "mal_accounts",
        "mal_active",
        "simkl_token",
        "simkl_user",
        "simkl_cached_list",
        "simkl_cached_time",
        "simkl_accounts",
        "simkl_active",
        "SIMKL_API_CACHE",
        "ANIWAVE_SIMKL_SYNC",
        "open_subtitles_user",
        "opensubtitles_accounts",
        "opensubtitles_active",
        "subdl_user",
        "subdl_accounts",
        "subdl_active",
        "biometric_key",
        "nginx_user",
        "download_path_key",
        "download_path_key_visual",
        "backup_path_key",
        "backup_dir_path_key",
        "cs3-votes",
        "last_sync_api",
        "last_click_action",
        "last_opened_id",
        "library_folder",
        "result_resume_watching_migrated",
        "jsdelivr_proxy_key",
        "fshare_setup",
        "fshare_token",
        "bluphim_token",
        "device_id",
        "sync_token",
        "sync_project_num",
        "sync_project_id",
        "sync_item_id",
        "sync_device_id",
        "restore_device",
        "backup_device",
        "download_info",
        "download_resume",
        "download_q_resume",
        "download_episode_cache",
        "prerelease_update",
        "data_store_helper/account_key_index",
        "VERSION_NAME",
        "FILES_TO_DELETE_KEY",
        "HAS_DONE_SETUP",
        "ULTIMA_CURRENT_META_PROVIDERS",
        "ULTIMA_EXTENSIONS_LIST",
        "ULTIMA_CURRENT_MEDIA_PROVIDERS",
        "ULTIMA_WATCH_SYNC_CREDS",
        "used_fstream_providers_v3",
        "fstream_version",
    )

    private val syncCategoryKeys = mapOf(
        SyncCategory.EXTENSIONS to listOf(
            "PLUGINS_KEY", "PLUGINS_KEY_LOCAL", "repositories",
        ),
        SyncCategory.BOOKMARKS to listOf(
            "result_favorites_state_data", "result_watch_state", "result_watch_state_data",
        ),
        SyncCategory.RESUME_WATCHING to listOf(
            "result_resume_watching", "video_pos_dur",
            "download_header_cache", "download_resume",
            "result_season", "result_dub", "result_episode",
        ),
        SyncCategory.SEARCH_HISTORY to listOf(
            "search_history",
        ),
        SyncCategory.SETTINGS_PLAYER to listOf(
            "player", "品質",
        ),
        SyncCategory.SETTINGS_SUBTITLES to listOf(
            "subtitle", "subs",
        ),
        SyncCategory.SETTINGS_THEME to listOf(
            "theme", "dark", "accent", "amoled",
        ),
        SyncCategory.SETTINGS_LAYOUT to listOf(
            "layout", "home", "grid", "card", "landing",
        ),
        SyncCategory.SETTINGS_DOWNLOADS to listOf(
            "download", "torrent",
        ),
    )

    fun classifyKey(key: String): SyncCategory? {
        if (nonTransferableKeys.any { key.startsWith(it) || key == it }) return null

        for ((category, patterns) in syncCategoryKeys) {
            if (patterns.any { key.contains(it, ignoreCase = true) }) return category
        }

        return SyncCategory.SETTINGS_GENERAL
    }

    fun isResumeRelevant(key: String, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?): Boolean {
        if (resumeWatching == null) return true
        return when {
            key.contains("download_header_cache") -> {
                val id = key.split("/").getOrNull(1)?.toIntOrNull()
                id?.let { intId ->
                    resumeWatching.any { if (it.parentId != null) it.parentId == intId else it.id == intId }
                } ?: false
            }
            key.contains("video_pos_dur") -> {
                val id = key.split("/").getOrNull(2)?.toIntOrNull()
                id?.let { intId -> resumeWatching.any { it.id == intId } } ?: false
            }
            key.contains("result_season") || key.contains("result_dub") || key.contains("result_episode") -> {
                val id = key.split("/").getOrNull(2)?.toIntOrNull()
                id?.let { intId -> resumeWatching.any { it.parentId == intId } } ?: false
            }
            else -> true
        }
    }

    fun extractPrefs(
        context: Context,
        category: SyncCategory,
        resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?,
    ): Pair<Map<String, *>, Boolean> {
        val isSettings = category in listOf(
            SyncCategory.SETTINGS_PLAYER, SyncCategory.SETTINGS_SUBTITLES,
            SyncCategory.SETTINGS_THEME, SyncCategory.SETTINGS_LAYOUT,
            SyncCategory.SETTINGS_DOWNLOADS, SyncCategory.SETTINGS_GENERAL,
        )
        val prefs = if (isSettings) context.getDefaultSharedPrefs().all else context.getSharedPrefs().all
        val filtered = prefs.filter { (key, _) ->
            classifyKey(key) == category && isResumeRelevant(key, resumeWatching)
        }
        return filtered to isSettings
    }

    fun buildBackupVars(map: Map<String, *>, keyTs: MutableMap<String, Long>, now: Long): BackupVars {
        return BackupVars(
            bool = map.filterValues { it is Boolean }.mapValues { (k, v) ->
                keyTs.putIfAbsent(k, now); v as Boolean
            },
            int = map.filterValues { it is Int }.mapValues { (k, v) ->
                keyTs.putIfAbsent(k, now); v as Int
            },
            string = map.filterValues { it is String }.mapValues { (k, v) ->
                keyTs.putIfAbsent(k, now); v as String
            },
            float = map.filterValues { it is Float }.mapValues { (k, v) ->
                keyTs.putIfAbsent(k, now); v as Float
            },
            long = map.filterValues { it is Long }.mapValues { (k, v) ->
                keyTs.putIfAbsent(k, now); v as Long
            },
            stringSet = map.filterValues { it is Set<*> }.mapValues { (k, v) ->
                keyTs.putIfAbsent(k, now); (v as? Set<*>)?.filterIsInstance<String>()?.toSet()
            },
        )
    }

    fun getBackupForCategory(
        context: Context,
        category: SyncCategory,
        resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?,
    ): BackupFile? {
        val now = System.currentTimeMillis()
        val keyTs = mutableMapOf<String, Long>()

        for (isSettings in listOf(false, true)) {
            val prefs = if (isSettings) context.getDefaultSharedPrefs().all else context.getSharedPrefs().all
            for ((key, _) in prefs) {
                if (classifyKey(key) == category && isResumeRelevant(key, resumeWatching)) {
                    keyTs[key] = now
                }
            }
        }

        if (keyTs.isEmpty()) return null

        val (dataMap, isDataSettings) = extractPrefs(context, category, resumeWatching)
        val (settingsMap, isSettingsSettings) = extractPrefs(context, category, resumeWatching)

        val datastore = if (!isDataSettings && !isSettingsSettings) {
            buildBackupVars(dataMap, keyTs, now)
        } else if (!isDataSettings) {
            buildBackupVars(dataMap, keyTs, now)
        } else {
            BackupVars(null, null, null, null, null, null)
        }

        val settings = if (isDataSettings || isSettingsSettings) {
            buildBackupVars(settingsMap, keyTs, now)
        } else {
            BackupVars(null, null, null, null, null, null)
        }

        // Deduplicate: if key is in both sections, keep only datastore version with higher ts
        val mergedKeyTs = keyTs.toMap()
        return BackupFile(datastore = datastore, settings = settings, keyTs = mergedKeyTs)
    }

    fun restoreCategoryData(context: Context, category: SyncCategory, backup: BackupFile?) {
        if (backup == null) return

        val isSettings = category in listOf(
            SyncCategory.SETTINGS_PLAYER, SyncCategory.SETTINGS_SUBTITLES,
            SyncCategory.SETTINGS_THEME, SyncCategory.SETTINGS_LAYOUT,
            SyncCategory.SETTINGS_DOWNLOADS, SyncCategory.SETTINGS_GENERAL,
        )

        if (!isSettings) {
            restoreMap(context, backup.datastore.bool, false)
            restoreMap(context, backup.datastore.int, false)
            restoreMap(context, backup.datastore.string, false)
            restoreMap(context, backup.datastore.float, false)
            restoreMap(context, backup.datastore.long, false)
            restoreMap(context, backup.datastore.stringSet, false)
        } else {
            restoreMap(context, backup.settings.bool, true)
            restoreMap(context, backup.settings.int, true)
            restoreMap(context, backup.settings.string, true)
            restoreMap(context, backup.settings.float, true)
            restoreMap(context, backup.settings.long, true)
            restoreMap(context, backup.settings.stringSet, true)
        }
    }

    fun <T> restoreMap(context: Context, map: Map<String, T>?, isEditingAppSettings: Boolean) {
        if (map == null) return
        val editor2 = editor(context, isEditingAppSettings)
        map.forEach { (key, value) ->
            if (classifyKey(key) != null) {
                editor2.setKeyRaw(key, value)
            }
        }
        editor2.apply()
    }

    fun editor(context : Context, isEditingAppSettings: Boolean = false) : Editor {
        val editor: SharedPreferences.Editor = if (isEditingAppSettings) context.getDefaultSharedPrefs().edit() else context.getSharedPrefs().edit()
        return Editor(editor)
    }

    fun getBackup(context: Context?, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?): BackupFile? {
        if (context == null) return null

        val now = System.currentTimeMillis()
        val keyTs = mutableMapOf<String, Long>()

        val allData = context.getSharedPrefs().all
        val allSettings = context.getDefaultSharedPrefs().all

        for ((key, _) in allData) {
            if (classifyKey(key) != null && isResumeRelevant(key, resumeWatching)) {
                keyTs[key] = now
            }
        }
        for ((key, _) in allSettings) {
            if (classifyKey(key) != null && isResumeRelevant(key, resumeWatching)) {
                keyTs[key] = now
            }
        }

        val dataFiltered = allData.filter { (k, _) -> classifyKey(k) != null && isResumeRelevant(k, resumeWatching) }
        val settingsFiltered = allSettings.filter { (k, _) -> classifyKey(k) != null && isResumeRelevant(k, resumeWatching) }

        val allDataSorted = BackupVars(
            dataFiltered.filter { it.value is Boolean } as? Map<String, Boolean>,
            dataFiltered.filter { it.value is Int } as? Map<String, Int>,
            dataFiltered.filter { it.value is String } as? Map<String, String>,
            dataFiltered.filter { it.value is Float } as? Map<String, Float>,
            dataFiltered.filter { it.value is Long } as? Map<String, Long>,
            dataFiltered.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>
        )

        val allSettingsSorted = BackupVars(
            settingsFiltered.filter { it.value is Boolean } as? Map<String, Boolean>,
            settingsFiltered.filter { it.value is Int } as? Map<String, Int>,
            settingsFiltered.filter { it.value is String } as? Map<String, String>,
            settingsFiltered.filter { it.value is Float } as? Map<String, Float>,
            settingsFiltered.filter { it.value is Long } as? Map<String, Long>,
            settingsFiltered.filter { it.value as? Set<String> != null } as? Map<String, Set<String>>
        )

        return BackupFile(allDataSorted, allSettingsSorted, keyTs)
    }

    fun restore(context: Context?, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean) {
        if (context == null) return
        if (restoreSettings) {
            restoreMap(context, backupFile.settings.bool, true)
            restoreMap(context, backupFile.settings.int, true)
            restoreMap(context, backupFile.settings.string, true)
            restoreMap(context, backupFile.settings.float, true)
            restoreMap(context, backupFile.settings.long, true)
            restoreMap(context, backupFile.settings.stringSet, true)
        }
        if (restoreDataStore) {
            restoreMap(context, backupFile.datastore.bool, false)
            restoreMap(context, backupFile.datastore.int, false)
            restoreMap(context, backupFile.datastore.string, false)
            restoreMap(context, backupFile.datastore.float, false)
            restoreMap(context, backupFile.datastore.long, false)
            restoreMap(context, backupFile.datastore.stringSet, false)
        }
    }
}
