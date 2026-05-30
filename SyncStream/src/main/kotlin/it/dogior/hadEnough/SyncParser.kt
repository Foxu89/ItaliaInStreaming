package it.dogior.hadEnough

import com.fasterxml.jackson.annotation.JsonProperty

enum class SyncCategory(val id: String) {
    EXTENSIONS("extensions"),
    BOOKMARKS("bookmarks"),
    RESUME_WATCHING("resume_watching"),
    SEARCH_HISTORY("search_history"),
    SETTINGS_PLAYER("settings_player"),
    SETTINGS_SUBTITLES("settings_subtitles"),
    SETTINGS_THEME("settings_theme"),
    SETTINGS_LAYOUT("settings_layout"),
    SETTINGS_DOWNLOADS("settings_downloads"),
    SETTINGS_GENERAL("settings_general");

    companion object {
        fun fromId(id: String): SyncCategory? = entries.find { it.id == id }
        fun backupKey(cat: SyncCategory) = "sync_backup_${cat.id}"
        fun restoreKey(cat: SyncCategory) = "sync_restore_${cat.id}"
    }
}

data class CategoryData(
    @JsonProperty("ts") val ts: Long,
    @JsonProperty("data") val data: String,
)

data class SyncPayloadV2(
    @JsonProperty("v") val v: Int = 2,
    @JsonProperty("deviceId") val deviceId: String,
    @JsonProperty("categories") val categories: Map<String, CategoryData>,
)

data class BackupVars(
    @JsonProperty("_Bool") val bool: Map<String, Boolean>?,
    @JsonProperty("_Int") val int: Map<String, Int>?,
    @JsonProperty("_String") val string: Map<String, String>?,
    @JsonProperty("_Float") val float: Map<String, Float>?,
    @JsonProperty("_Long") val long: Map<String, Long>?,
    @JsonProperty("_StringSet") val stringSet: Map<String, Set<String>?>?,
)

data class BackupFile(
    @JsonProperty("datastore") val datastore: BackupVars,
    @JsonProperty("settings") val settings: BackupVars,
    @JsonProperty("_keyTs") val keyTs: Map<String, Long>? = null,
) {
    fun allTypedMaps(): List<Pair<Boolean, Map<String, *>>> = listOf(
        false to (datastore.bool ?: emptyMap()),
        false to (datastore.int ?: emptyMap()),
        false to (datastore.string ?: emptyMap()),
        false to (datastore.float ?: emptyMap()),
        false to (datastore.long ?: emptyMap()),
        false to (datastore.stringSet ?: emptyMap()),
        true to (settings.bool ?: emptyMap()),
        true to (settings.int ?: emptyMap()),
        true to (settings.string ?: emptyMap()),
        true to (settings.float ?: emptyMap()),
        true to (settings.long ?: emptyMap()),
        true to (settings.stringSet ?: emptyMap()),
    )

    fun getKeyTs(key: String): Long = keyTs?.get(key) ?: 0L

    companion object {
        fun merge(local: BackupFile?, cloud: BackupFile?): BackupFile? {
            if (local == null) return cloud
            if (cloud == null) return local

            val localKeyTs = local.keyTs ?: emptyMap()
            val cloudKeyTs = cloud.keyTs ?: emptyMap()
            val resultKeyTs = mutableMapOf<String, Long>()

            fun <T> mergeMap(localMap: Map<String, T>, cloudMap: Map<String, T>): Map<String, T> {
                val result = mutableMapOf<String, T>()
                val allKeys = localMap.keys + cloudMap.keys
                for (key in allKeys.distinct()) {
                    val lVal = localMap[key]
                    val cVal = cloudMap[key]
                    when {
                        lVal != null && cVal == null -> {
                            result[key] = lVal
                            resultKeyTs[key] = localKeyTs[key] ?: 0L
                        }
                        lVal == null && cVal != null -> {
                            result[key] = cVal
                            resultKeyTs[key] = cloudKeyTs[key] ?: 0L
                        }
                        lVal != null && cVal != null -> {
                            val lTs = localKeyTs[key] ?: 0L
                            val cTs = cloudKeyTs[key] ?: 0L
                            if (lTs >= cTs) {
                                result[key] = lVal
                                resultKeyTs[key] = lTs
                            } else {
                                result[key] = cVal
                                resultKeyTs[key] = cTs
                            }
                        }
                    }
                }
                return result
            }

            fun mergeStringSetMap(localMap: Map<String, Set<String>?>, cloudMap: Map<String, Set<String>?>): Map<String, Set<String>?> {
                val result = mutableMapOf<String, Set<String>?>()
                val allKeys = localMap.keys + cloudMap.keys
                for (key in allKeys.distinct()) {
                    val lVal = localMap[key]
                    val cVal = cloudMap[key]
                    when {
                        lVal != null && cVal == null -> {
                            result[key] = lVal
                            resultKeyTs[key] = localKeyTs[key] ?: 0L
                        }
                        lVal == null && cVal != null -> {
                            result[key] = cVal
                            resultKeyTs[key] = cloudKeyTs[key] ?: 0L
                        }
                        lVal != null && cVal != null -> {
                            val lTs = localKeyTs[key] ?: 0L
                            val cTs = cloudKeyTs[key] ?: 0L
                            if (lTs >= cTs) {
                                result[key] = lVal
                                resultKeyTs[key] = lTs
                            } else {
                                result[key] = cVal
                                resultKeyTs[key] = cTs
                            }
                        }
                    }
                }
                return result
            }

            val mergedDatastore = BackupVars(
                bool = mergeMap(local.datastore.bool ?: emptyMap(), cloud.datastore.bool ?: emptyMap()),
                int = mergeMap(local.datastore.int ?: emptyMap(), cloud.datastore.int ?: emptyMap()),
                string = mergeMap(local.datastore.string ?: emptyMap(), cloud.datastore.string ?: emptyMap()),
                float = mergeMap(local.datastore.float ?: emptyMap(), cloud.datastore.float ?: emptyMap()),
                long = mergeMap(local.datastore.long ?: emptyMap(), cloud.datastore.long ?: emptyMap()),
                stringSet = mergeStringSetMap(local.datastore.stringSet ?: emptyMap(), cloud.datastore.stringSet ?: emptyMap()),
            )
            val mergedSettings = BackupVars(
                bool = mergeMap(local.settings.bool ?: emptyMap(), cloud.settings.bool ?: emptyMap()),
                int = mergeMap(local.settings.int ?: emptyMap(), cloud.settings.int ?: emptyMap()),
                string = mergeMap(local.settings.string ?: emptyMap(), cloud.settings.string ?: emptyMap()),
                float = mergeMap(local.settings.float ?: emptyMap(), cloud.settings.float ?: emptyMap()),
                long = mergeMap(local.settings.long ?: emptyMap(), cloud.settings.long ?: emptyMap()),
                stringSet = mergeStringSetMap(local.settings.stringSet ?: emptyMap(), cloud.settings.stringSet ?: emptyMap()),
            )

            return BackupFile(datastore = mergedDatastore, settings = mergedSettings, keyTs = resultKeyTs)
        }
    }
}

data class APIRes(
    @JsonProperty("data") var data: Data?,
    @JsonProperty("errors") var errors: Array<Error>?,
) {
    data class Data(
        @JsonProperty("viewer") var viewer: Viewer?,
        @JsonProperty("addProjectV2DraftIssue") var issue: Issue?,
        @JsonProperty("updateProjectV2DraftIssue") var updateIssue: UpdateIssue?,
        @JsonProperty("deleteProjectV2Item") var delItem: DelItem?
    ) {
        data class Viewer(@JsonProperty("projectV2") var projectV2: ProjectV2) {
            data class ProjectV2(
                @JsonProperty("id") var id: String,
                @JsonProperty("items") var items: Items?
            ) {
                data class Items(
                    @JsonProperty("nodes") var nodes: Array<Node>?,
                ) {
                    data class Node(
                        @JsonProperty("id") var id: String,
                        @JsonProperty("content") var content: Content
                    ) {
                        data class Content(
                            @JsonProperty("id") var id: String,
                            @JsonProperty("title") var title: String,
                            @JsonProperty("bodyText") var bodyText: String,
                        )
                    }
                }
            }
        }
        data class Issue(@JsonProperty("projectItem") var projectItem: ProjectItem) {
            data class ProjectItem(
                @JsonProperty("id") var id: String,
                @JsonProperty("content") var content: Content
            ) {
                data class Content(
                    @JsonProperty("id") var id: String
                )
            }
        }
        data class UpdateIssue(@JsonProperty("draftIssue") var draftIssue: DraftIssue) {
            data class DraftIssue(
                @JsonProperty("id") var id: String
            )
        }
        data class DelItem(@JsonProperty("deletedItemId") var deletedItemId: String)
    }

    data class Error(
        @JsonProperty("message") var message: String?
    )
}

data class SyncDevice(
    @JsonProperty("name") var name: String,
    @JsonProperty("deviceId") var deviceId: String,
    @JsonProperty("itemId") var itemId: String,
    @JsonProperty("syncedData") var syncedData: String? = null
)
