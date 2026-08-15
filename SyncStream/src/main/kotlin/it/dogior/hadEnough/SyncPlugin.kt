@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.os.*
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.ui.home.HomeViewModel.Companion.getResumeWatching
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.*

private const val TAG = "SyncStream"

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    companion object {
        @Volatile
        internal var activePlugin: Plugin? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null

    private var counter = 0

    /**
     * Il primo restore deve completare prima di iniziare a fare backup, altrimenti
     * un dispositivo appena registrato potrebbe sovrascrivere il cloud con dati vuoti.
     */
    @Volatile
    private var initialSyncDone = false

    @Volatile
    private var runnableStarted = false

    private val runnable = object : Runnable {
        override fun run() {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val currentResumeWatching = getResumeWatching()
                        if (currentResumeWatching != lastResumeWatching) {
                            counter = 0
                            backupDevice(true)
                            lastResumeWatching = currentResumeWatching
                        }
                        counter++
                        if (counter >= 12) {
                            counter = 0
                            backupDevice(true)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "runnable error: ${e.message}")
                    }
                }
                handler.postDelayed(this, 5000)
            } catch (e: Exception) {
            }
        }
    }

    private fun backupDevice(unused: Boolean) {
        if (!initialSyncDone) return
        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (getKey<String>("backup_device") == "true" && ApiUtils.isLoggedIn()) {
                        val backup = BackupUtils.getBackup(context, getResumeWatching()) ?: return@launch
                        val envelope = SyncEnvelope(System.currentTimeMillis(), backup)
                        val result = ApiUtils.syncThisDevice(envelope.toJson())
                        if (result.first) {
                            setKey("sync_last_restore_at", envelope.updatedAt)
                            Log.i(TAG, "backup inviato (${envelope.updatedAt})")
                        } else {
                            Log.w(TAG, "backup FALLITO: ${result.second}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "backup error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "backupDevice error: ${e.message}")
        }
    }

    private fun ensureRunnableRunning() {
        if (runnableStarted) return
        runnableStarted = true
        handler.post(runnable)
    }

    /**
     * Sincronizzazione iniziale: fetch dei nodi e restore non distruttivo del backup
     * più recente, fuori dal main thread. Imposta initialSyncDone al termine.
     */
    private fun performInitialSync() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!ApiUtils.isLoggedIn()) return@launch
                val devices = ApiUtils.fetchDevices()
                val node = devices?.firstOrNull()
                if (node != null) {
                    setKey("sync_item_id", node.itemId ?: "")
                    setKey("sync_device_id", node.deviceId ?: "")
                    if (getKey<String>("restore_device") == "true") {
                        if (ApiUtils.restoreFromDevice(context, node)) {
                            handler.post { MainActivity.bookmarksUpdatedEvent(true) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "initial sync error: ${e.message}")
            } finally {
                initialSyncDone = true
                if (ApiUtils.isLoggedIn()) {
                    handler.post { ensureRunnableRunning() }
                }
            }
        }
    }

    override fun load(context: Context) {
        val packageName = context.packageName
        setKey("device_id", getDeviceId(packageName, context))
        MainActivity.bookmarksUpdatedEvent += ::backupDevice
        MainActivity.afterPluginsLoadedEvent += ::backupDevice
        MainActivity.mainPluginsLoadedEvent += ::backupDevice
        MainActivity.reloadHomeEvent += ::backupDevice
        MainActivity.reloadAccountEvent += ::backupDevice
        performInitialSync()
    }

    init {
        this.openSettings = {
            try {
                activePlugin = this
                val activity = it as? AppCompatActivity
                if (activity != null) {
                    val frag = SyncSettingsFragment()
                    frag.show(activity.supportFragmentManager, "Github")
                }
            } catch (e: Exception) {
            }
        }
    }

    /** Chiamato dopo un login riuscito: sblocca i backup e avvia il polling. */
    fun onLoginCompleted() {
        initialSyncDone = true
        handler.post { ensureRunnableRunning() }
    }
}
