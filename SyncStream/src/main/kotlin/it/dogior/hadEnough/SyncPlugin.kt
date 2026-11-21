@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.content.SharedPreferences
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
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.utils.DataStore.getDefaultSharedPrefs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

private const val TAG = "SyncStream"

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    companion object {
        @Volatile
        internal var activePlugin: Plugin? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private val pollDelay = 5_000L

    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null

    private var counter = 0

    private var pullCounter = 0

    @Volatile
    private var initialSyncDone = false

    @Volatile
    private var runnableStarted = false

    @Volatile
    private var cycleRunning = false

    private val runnable = object : Runnable {
        override fun run() {
            try {
                if (!cycleRunning) {
                    cycleRunning = true
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            performCycle()
                        } catch (e: Exception) {
                            Log.w(TAG, "🔄 runnable error: ${e.message}")
                        } finally {
                            cycleRunning = false
                        }
                    }
                }
                handler.postDelayed(this, pollDelay)
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun performCycle() {
        val currentResumeWatching = getResumeWatching()
        val changed = currentResumeWatching != lastResumeWatching
        if (changed) {
            lastResumeWatching = currentResumeWatching
            counter = 0
        }
        counter++
        if (changed || counter >= 12) {
            counter = 0
            performBackup()
        }
        performPullIfEnabled()
    }

    private val backupMutex = Mutex()

    /**
     * Backup solo se i dati locali sono davvero cambiati dall'ultimo invio.
     * Prima di caricare controlla se nel cloud c'è qualcosa di più recente (da un altro
     * dispositivo): in quel caso lo unisce ai dati locali, così non lo sovrascrive.
     */
    private suspend fun performBackup() {
        if (!initialSyncDone) return
        if (getKey<String>("backup_device") != "true") return
        if (!ApiUtils.isLoggedIn()) return
        backupMutex.withLock {
            try {
                var hash = ApiUtils.localHash(context, getResumeWatching()) ?: return
                if (hash == getKey<String>("sync_last_hash")) return // niente di nuovo

                if (getKey<String>("restore_device") == "true") {
                    val newer = ApiUtils.remoteNewerNode()
                    if (newer != null) {
                        Log.i(TAG, "📥 nel cloud c'è un backup più nuovo: lo unisco prima di caricare")
                        // senza eliminare cose locali non ancora inviate
                        if (ApiUtils.restoreFromDevice(context, newer, reconcile = false)) {
                            handler.post { MainActivity.bookmarksUpdatedEvent(true) }
                        }
                        hash = ApiUtils.localHash(context, getResumeWatching()) ?: return
                    }
                }

                val envelope = ApiUtils.buildEnvelope(context, getResumeWatching()) ?: return
                Log.i(TAG, "📤 invio backup...")
                val result = ApiUtils.syncThisDevice(envelope.toJson())
                if (result.first) {
                    setKey("sync_last_restore_at", envelope.updatedAt)
                    setKey("sync_last_hash", hash)
                    Log.i(TAG, "📤 backup inviato ✅ (${envelope.updatedAt})")
                } else {
                    Log.w(TAG, "📤 backup FALLITO ❌: ${result.second}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "📤 backup error: ${e.message}")
            }
        }
    }

    // ---- rileva le modifiche ai dati dell'app (preferiti, progressi, impostazioni...) ----
    private val backupScheduled = AtomicBoolean(false)

    private val prefListener = object : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            if (key == null || !initialSyncDone) return
            if (BackupUtils.nonTransferableKeys.any { key.contains(it) }) return
            scheduleBackup()
        }
    }

    /** Raggruppa le modifiche ravvicinate (es. progressi video) in un solo invio dopo ~15 secondi. */
    private fun scheduleBackup() {
        if (!backupScheduled.compareAndSet(false, true)) return
        handler.postDelayed({
            backupScheduled.set(false)
            CoroutineScope(Dispatchers.IO).launch { performBackup() }
        }, 15_000L)
    }

    private suspend fun performPullIfEnabled() {
        if (getKey<String>("restore_device") != "true") return
        if ((getKey<Boolean>("auto_pull_enabled") ?: false) == false) return
        pullCounter++
        val interval = getKey<Long>("auto_pull_seconds") ?: 30_000L
        val ticks = (interval / pollDelay).toInt().coerceAtLeast(1)
        if (pullCounter < ticks) return
        pullCounter = 0
        try {
            if (!ApiUtils.isLoggedIn()) return
            Log.i(TAG, "📥 pull periodico...")
            val node = ApiUtils.remoteNewerNode()
            if (node == null) {
                Log.i(TAG, "📥 nessun cambiamento da scaricare")
                return
            }
            if (ApiUtils.restoreFromDevice(context, node)) {
                // i dati locali ora coincidono col cloud: non va ricaricato nulla
                ApiUtils.localHash(context, getResumeWatching())?.let { setKey("sync_last_hash", it) }
                handler.post { MainActivity.bookmarksUpdatedEvent(true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "📥 pull error: ${e.message}")
        }
    }

    private fun backupDevice(unused: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            performBackup()
        }
    }

    private fun ensureRunnableRunning() {
        if (runnableStarted) return
        runnableStarted = true
        handler.post(runnable)
    }

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
                            ApiUtils.localHash(context, getResumeWatching())?.let { setKey("sync_last_hash", it) }
                            handler.post { MainActivity.bookmarksUpdatedEvent(true) }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚡ initial sync error: ${e.message}")
            } finally {
                initialSyncDone = true
                if (ApiUtils.isLoggedIn()) {
                    Log.i(TAG, "⚡ sync iniziale completata")
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
        try {
            context.getSharedPrefs().registerOnSharedPreferenceChangeListener(prefListener)
            context.getDefaultSharedPrefs().registerOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) {
            Log.w(TAG, "listener non registrato: ${e.message}")
        }
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

    fun onLoginCompleted() {
        initialSyncDone = true
        handler.post { ensureRunnableRunning() }
    }
}
