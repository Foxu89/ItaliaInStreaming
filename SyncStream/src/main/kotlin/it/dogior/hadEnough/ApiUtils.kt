@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.util.Base64
import android.content.Context
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.fasterxml.jackson.module.kotlin.readValue

object ApiUtils {
    private fun Any.toStringData(): String {
        return mapper.writeValueAsString(this)
    }

    private suspend fun apiCall(query: String): APIRes? {
        try {
            val token = getKey<String>("sync_token")
            val apiUrl = "https://api.github.com/graphql"
            val header = mapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $token"
            )
            val data = """ { "query": ${query} } """
            val res = app.post(apiUrl, headers = header, json = data)
            return res.parsedSafe<APIRes>()
        } catch (e: Exception) {
            return null
        }
    }

    fun isLoggedIn(): Boolean {
        val token = getKey<String>("sync_token")
        val projectNum = getKey<String>("sync_project_num")
        val projectId = getKey<String>("sync_project_id")
        return !(token.isNullOrEmpty() || projectNum.isNullOrEmpty() || projectId.isNullOrEmpty())
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String?> {
        var failure = false to "Project not found"
        var failureToken = false to "Github token is wrong"
        val projectNum = getKey<String>("sync_project_num")
        val query = """ query Viewer { viewer { projectV2(number: ${projectNum}) { id } } } """
        val res = apiCall(query.toStringData()) ?: return failureToken
        val projectId = res.data?.viewer?.projectV2?.id ?: return failure
        setKey("sync_project_id", projectId)
        val deviceId = getKey<String>("device_id")

        val existing = findDevice(deviceId)
        if (existing != null) {
            setKey("sync_item_id", existing.itemId)
            setKey("sync_device_id", existing.deviceId)
        } else {
            val syncData = buildInitialPayload(deviceId)
            val dataJson = mapper.writeValueAsString(syncData)
            val bodyB64 = Base64.encodeToString(dataJson.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
            val mutation = """ mutation AddProjectV2DraftIssue { addProjectV2DraftIssue( input: { projectId: "$projectId", title: "$deviceId", body: "$bodyB64" } ) { projectItem { id content { ... on DraftIssue { id } } } } } """
            val mutationRes = apiCall(mutation.toStringData()) ?: return failureToken
            val itemId = mutationRes.data?.issue?.projectItem?.id ?: return false to mutationRes.errors?.get(0)?.message?.toString()
            val draftId = mutationRes.data?.issue?.projectItem?.content?.id ?: return false to mutationRes.errors?.get(0)?.message?.toString()
            setKey("sync_item_id", itemId)
            setKey("sync_device_id", draftId)

            // First time: try to restore from any existing device
            val devices = fetchDevices()
            if (devices?.isNotEmpty() == true) {
                val otherDevice = devices.find { it.deviceId != deviceId && it.syncedData != null }
                if (otherDevice != null) {
                    try {
                        val payload = mapper.readValue<SyncPayloadV2>(otherDevice.syncedData ?: "")
                        restoreCategories(context, payload)
                    } catch (_: Exception) {}
                }
            }
        }
        return true to "Dispositivo registrato correttamente"
    }

    private fun buildInitialPayload(deviceId: String): SyncPayloadV2 {
        return SyncPayloadV2(
            v = 2,
            deviceId = deviceId,
            categories = SyncCategory.entries.associate { cat ->
                cat.id to CategoryData(ts = System.currentTimeMillis(), data = "")
            }
        )
    }

    private suspend fun restoreCategories(context: Context?, payload: SyncPayloadV2) {
        if (context == null) return
        for ((catId, catData) in payload.categories) {
            if (catData.data.isBlank()) continue
            val category = SyncCategory.fromId(catId) ?: continue
            if (getKey<String>(SyncCategory.restoreKey(category)) != "true") continue
            try {
                val jsonBytes = Base64.decode(catData.data, Base64.URL_SAFE or Base64.NO_WRAP)
                val cloudBackup = mapper.readValue<BackupFile>(String(jsonBytes, Charsets.UTF_8))
                val localBackup = BackupUtils.getBackupForCategory(context, category, getResumeWatching())
                val merged = BackupFile.merge(localBackup, cloudBackup)
                if (merged != null) {
                    BackupUtils.restoreCategoryData(context, category, merged)
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun pushAllCategories(context: Context?) {
        if (!isLoggedIn() || context == null) return
        val deviceId = getKey<String>("device_id")
        val draftIssueId = getKey<String>("sync_device_id") ?: return

        val categories = mutableMapOf<String, CategoryData>()
        for (category in SyncCategory.entries) {
            if (getKey<String>(SyncCategory.backupKey(category)) != "true") continue
            val backup = BackupUtils.getBackupForCategory(context, category, getResumeWatching())
            if (backup != null) {
                val jsonStr = mapper.writeValueAsString(backup)
                val b64 = Base64.encodeToString(jsonStr.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
                categories[category.id] = CategoryData(ts = System.currentTimeMillis(), data = b64)
            }
        }

        val payload = SyncPayloadV2(v = 2, deviceId = deviceId, categories = categories)
        val payloadJson = mapper.writeValueAsString(payload)
        val bodyB64 = Base64.encodeToString(payloadJson.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val mutation = """ mutation UpdateProjectV2DraftIssue { updateProjectV2DraftIssue( input: { draftIssueId: "$draftIssueId", title: "$deviceId", body: "$bodyB64" } ) { draftIssue { id } } } """
        apiCall(mutation.toStringData())
    }

    suspend fun pullAndMergeCategories(context: Context?) {
        if (!isLoggedIn() || context == null) return

        val deviceId = getKey<String>("device_id")
        val devices = fetchDevices() ?: return

        // Trova il primo dispositivo non-nostro con dati
        val sourceDevice = devices.find { it.deviceId != deviceId && it.syncedData != null } ?: return
        try {
            val payload = mapper.readValue<SyncPayloadV2>(sourceDevice.syncedData ?: "")
            for ((catId, catData) in payload.categories) {
                if (catData.data.isBlank()) continue
                val category = SyncCategory.fromId(catId) ?: continue
                if (getKey<String>(SyncCategory.restoreKey(category)) != "true") continue

                val jsonBytes = Base64.decode(catData.data, Base64.URL_SAFE or Base64.NO_WRAP)
                val cloudBackup = try { mapper.readValue<BackupFile>(String(jsonBytes, Charsets.UTF_8)) } catch (_: Exception) { continue }
                val localBackup = BackupUtils.getBackupForCategory(context, category, getResumeWatching())
                val merged = BackupFile.merge(localBackup, cloudBackup) ?: continue

                // Scrivi merged solo se è diverso dal locale (ha ricevuto modifiche dal cloud)
                if (merged != localBackup) {
                    BackupUtils.restoreCategoryData(context, category, merged)
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun syncThisDevice(syncData: String): Pair<Boolean, String?> {
        val failure = false to "Error sync this device id: ${getKey<String>("device_id")}"
        if (!isLoggedIn()) return failure
        val data = Base64.encodeToString(syncData.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        val deviceId = getKey<String>("sync_device_id")
        val query = """ mutation UpdateProjectV2DraftIssue { updateProjectV2DraftIssue( input: { draftIssueId: "$deviceId", title: "${getKey<String>("device_id")}", body: "$data" } ) { draftIssue { id } } } """
        apiCall(query.toStringData()) ?: return failure
        return true to "Sync success"
    }

    suspend fun fetchDevices(): List<SyncDevice>? {
        if (!isLoggedIn()) return null
        val projectNum = getKey<String>("sync_project_num")
        val query = """ query User { viewer { projectV2(number: ${projectNum}) { id items(first: 50) { nodes { id content { ... on DraftIssue { id title bodyText } } } totalCount } } } } """
        val res = apiCall(query.toStringData())
        return res?.data?.viewer?.projectV2?.items?.nodes?.mapNotNull { node ->
            try {
                val rawBody = node.content.bodyText
                // Try v2 first
                val syncPayload = try {
                    mapper.readValue<SyncPayloadV2>(rawBody)
                } catch (_: Exception) {
                    null
                }
                if (syncPayload != null) {
                    val payloadJson = mapper.writeValueAsString(syncPayload)
                    SyncDevice(node.content.title, node.content.id, node.id, payloadJson)
                } else {
                    // Fallback v1 (legacy)
                    val data = Base64.decode(rawBody, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
                    SyncDevice(node.content.title, node.content.id, node.id, data)
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun findDevice(deviceId: String): SyncDevice? {
        return fetchDevices()?.find { it.name == deviceId }
    }
}
