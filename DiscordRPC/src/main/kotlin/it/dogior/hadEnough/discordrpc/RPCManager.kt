package it.dogior.hadEnough.discordrpc

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "DiscordRPC"

/**
 * Orchestra l'intera funzionalità:
 *  - gestisce la connessione al gateway Discord (con riconnessione);
 *  - polla il player di CloudStream per rilevare play/pausa/seek e cambio
 *    contenuto, poi compone il payload e lo invia (op 3);
 *  - quando il player si chiude o l'utente stoppa la riproduzione, azzera la
 *    presenza (activities vuote).
 *
 * Stesso approccio di WatchPartyManager: niente hook ufficiale sul player,
 * tutto via polling pubblico (getIsPlaying/getPosition) + metadati dal ViewModel.
 */
class RPCManager {

    enum class ConnectionState { DISCONNESSO, CONNESSIONE_IN_CORSO, CONNESSO, RICONNESSIONE_IN_CORSO }

    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null

    private var state = ConnectionState.DISCONNESSO
        set(value) {
            field = value
            onConnectionStateChanged?.invoke(value)
        }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var gateway: DiscordGateway? = null
    private var pollJob: Job? = null
    private var shouldStayConnected = false

    private var lastSentKey: String? = null
    private var lastPlaying: Boolean? = null
    private var lastMeta: ResultEpisode? = null
    private var lastPushMs = 0L

    /** Riavvio della connessione da zero (usato da Login/Logout e all'avvio). */
    fun start() {
        if (!RPCSettings.enabled) return
        val accessToken = RPCSettings.token
        if (accessToken.isBlank()) return
        if (shouldStayConnected) return

        shouldStayConnected = true
        connectGateway(accessToken)
        startPolling()
    }

    fun stop() {
        shouldStayConnected = false
        stopPolling()
        gateway?.close()
        gateway = null
        state = ConnectionState.DISCONNESSO
        lastSentKey = null
        lastPlaying = null
        lastMeta = null
        lastPushMs = 0L
    }

    private fun connectGateway(accessToken: String) {
        state = ConnectionState.CONNESSIONE_IN_CORSO

        gateway = DiscordGateway(
            onReady = { username ->
                state = ConnectionState.CONNESSO
                mainHandler.post { pushCurrentPresence() }
            },
            onDispatch = { /* gestione futura di eventi, per ora non servono */ },
            onClosed = {
                mainHandler.post {
                    if (shouldStayConnected) scheduleReconnect(accessToken)
                }
            },
            onFailure = { t ->
                Log.w(TAG, "gateway failure: ${t.message}")
                mainHandler.post {
                    if (shouldStayConnected) scheduleReconnect(accessToken)
                }
            },
        )
        gateway?.connect(accessToken)
    }

    /** Riconnessione con backoff semplice (1s, 2s, 4s … tetto 15s). */
    private fun scheduleReconnect(accessToken: String) {
        if (!shouldStayConnected) return
        state = ConnectionState.RICONNESSIONE_IN_CORSO
        val delayMs = listOf(1000L, 2000L, 4000L, 8000L, 15000L).random()
        mainHandler.postDelayed({
            if (shouldStayConnected) {
                gateway?.resumeOrReopen(accessToken)
            }
        }, delayMs)
    }

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                withContext(Dispatchers.Main) { pollAndSend() }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun pollAndSend() {
        if (!shouldStayConnected) return

        if (!RPCSettings.enabled) {
            // master switch spento: puliamo la presenza se ce n'è una
            if (lastSentKey != null) {
                gateway?.sendPresence(PresenceBuilder.empty())
                lastSentKey = null
            }
            return
        }

        if (!PlayerMetaAccess.isPlayerScreenActive()) {
            if (lastSentKey != null) {
                gateway?.sendPresence(PresenceBuilder.empty())
                lastSentKey = null
            }
            lastPlaying = null
            return
        }

        val player = PlayerMetaAccess.currentPlayer() ?: return
        val meta = PlayerMetaAccess.currentEpisode()
        val playing = runCatching { player.getIsPlaying() }.getOrDefault(false)
        val position = runCatching { player.getPosition() }.getOrNull() ?: 0L

        val metaChanged = meta != lastMeta
        if (metaChanged) lastMeta = meta

        val prevPlaying = lastPlaying
        lastPlaying = playing

        val now = System.currentTimeMillis()
        val firstSend = lastSentKey == null

        if (playing) {
            // inviamo quando: primo tick, cambi meta, transizione pausa→play
            // oppure periodicamente (REFRESH_MS) per rimanere coerenti dopo i seek
            val needsFresh = firstSend || playingChanged(prevPlaying, playing) ||
                metaChanged || (now - lastPushMs > REFRESH_MS)
            if (needsFresh) {
                push(meta, position, playing)
                lastPushMs = now
            }
        } else {
            // pausa: rimuoviamo i timestamps ma lasciamo vedere cosa guardiamo
            if (firstSend || playingChanged(prevPlaying, playing) || metaChanged) {
                push(meta, position, playing)
                lastPushMs = now
            }
        }
    }

    /** Ritornare true quando la transizione play/pausa è reale (es. primo tick). */
    private fun playingChanged(prev: Boolean?, now: Boolean): Boolean =
        prev != null && prev != now

    private fun pushCurrentPresence() {
        if (!shouldStayConnected || !RPCSettings.enabled) return
        val gateway = gateway ?: return
        if (!gateway.isOpen) return

        val player = PlayerMetaAccess.currentPlayer()
        if (player == null || !PlayerMetaAccess.isPlayerScreenActive()) {
            gateway.sendPresence(PresenceBuilder.empty())
            return
        }
        val playing = runCatching { player.getIsPlaying() }.getOrDefault(false)
        val position = runCatching { player.getPosition() }.getOrNull() ?: 0L
        val meta = PlayerMetaAccess.currentEpisode()
        push(meta, position, playing)
    }

    private fun push(meta: ResultEpisode?, position: Long, playing: Boolean) {
        val g = gateway ?: return
        if (!g.isOpen) return

        val activities = PresenceBuilder.buildActivity(
            PresenceBuilder.PlayerState(meta = meta, positionMs = position, isPlaying = playing)
        )
        g.sendPresence(activities, status = "online")
        lastSentKey = meta?.let { "${it.parentId}:${it.id}" } ?: "unknown"
    }

    /** Rispedisce subito la presenza con le nuove impostazioni (usato dai toggle). */
    fun pushPreviewUpdate() {
        if (shouldStayConnected && RPCSettings.enabled) {
            mainHandler.post { pushCurrentPresence() }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1000L
        const val REFRESH_MS = 30_000L
    }
}