@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.os.*
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import kotlinx.coroutines.*

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private val handler = Handler(Looper.getMainLooper())

    private var lastBackupSnapshots = mutableMapOf<SyncCategory, BackupFile?>()
    private var dirtyCategories = mutableSetOf<SyncCategory>()
    private var pullCounter = 0

    private val runnable = object : Runnable {
        override fun run() {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    // Pull ogni 12 cicli (60s)
                    pullCounter++
                    val ctx = context ?: return@launch
                    if (pullCounter >= 12 && ApiUtils.isLoggedIn()) {
                        pullCounter = 0
                        ApiUtils.pullAndMergeCategories(ctx)
                        for (cat in SyncCategory.entries) {
                            if (getKey<String>(SyncCategory.backupKey(cat)) == "true") {
                                lastBackupSnapshots[cat] = BackupUtils.getBackupForCategory(ctx, cat, getResumeWatching())
                            }
                        }
                    }

                    val hasDirty = dirtyCategories.any { getKey<String>(SyncCategory.backupKey(it)) == "true" }
                    if (hasDirty) {
                        ApiUtils.pushAllCategories(ctx)
                        dirtyCategories.clear()
                    }

                    for (cat in SyncCategory.entries) {
                        if (getKey<String>(SyncCategory.backupKey(cat)) != "true") continue
                        val current = BackupUtils.getBackupForCategory(ctx, cat, getResumeWatching())
                        val lastSnapshot = lastBackupSnapshots[cat]
                        if (current != lastSnapshot) {
                            dirtyCategories.add(cat)
                            lastBackupSnapshots[cat] = current
                        }
                    }
                }
                handler.postDelayed(this, 5000)
            } catch (_: Exception) {}
        }
    }

    private fun markCategoryDirty(key: String) {
        val cat = BackupUtils.classifyKey(key) ?: return
        if (getKey<String>(SyncCategory.backupKey(cat)) == "true") {
            dirtyCategories.add(cat)
        }
    }

    private fun backupDevice(unused: Boolean) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                if (ApiUtils.isLoggedIn()) {
                    ApiUtils.pushAllCategories(context)
                    dirtyCategories.clear()
                }
            }
        } catch (_: Exception) {}
    }

    override fun load(context: Context) {
        val packageName = context.packageName
        setKey("device_id", getDeviceId(packageName, context))

        // Inizializza default per le categorie se non impostate
        for (cat in SyncCategory.entries) {
            if (getKey<String>(SyncCategory.backupKey(cat)) == null) {
                setKey(SyncCategory.backupKey(cat), "true")
            }
            if (getKey<String>(SyncCategory.restoreKey(cat)) == null) {
                setKey(SyncCategory.restoreKey(cat), "true")
            }
        }

        runBlocking {
            val devices: List<SyncDevice>? = ApiUtils.fetchDevices()
            if (devices?.isEmpty() == false && devices?.size ?: 0 > 0) {
                if (getKey<String>("restore_device") == "true") {
                    ApiUtils.syncProjectDetails(context)
                }
                // Snapshot iniziali
                for (cat in SyncCategory.entries) {
                    if (getKey<String>(SyncCategory.backupKey(cat)) == "true") {
                        lastBackupSnapshots[cat] = BackupUtils.getBackupForCategory(context, cat, getResumeWatching())
                    }
                }
                handler.post(runnable)
            } else {
                // Nessun dispositivo, registra
                ApiUtils.syncProjectDetails(context)
                for (cat in SyncCategory.entries) {
                    if (getKey<String>(SyncCategory.backupKey(cat)) == "true") {
                        lastBackupSnapshots[cat] = BackupUtils.getBackupForCategory(context, cat, getResumeWatching())
                    }
                }
                handler.post(runnable)
            }
        }

        // Event listeners
        MainActivity.bookmarksUpdatedEvent += ::backupDevice
        MainActivity.afterPluginsLoadedEvent += ::backupDevice
        MainActivity.mainPluginsLoadedEvent += ::backupDevice
        MainActivity.reloadHomeEvent += ::backupDevice
        MainActivity.reloadAccountEvent += ::backupDevice
    }

    init {
        this.openSettings = {
            try {
                val activity = it as? AppCompatActivity
                if (activity != null) {
                    val frag = SyncSettingsFragment(this)
                    frag.show(activity.supportFragmentManager, "SyncStream")
                }
            } catch (_: Exception) {}
        }
    }
}
