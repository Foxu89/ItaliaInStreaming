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

    /** Fallback per posizioni insane: elapsed misurato con l'orologio del device. */
    private var clockBaseMs = 0L
    private var elapsedBaseMs = 0L
    private var lastSanePositionMs = 0L

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
        Log.i(TAG, "🛑 manager stop")
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
                Log.i(TAG, "✅ CONNESSO con $username")
                state = ConnectionState.CONNESSO
                mainHandler.post { pushCurrentPresence() }
            },
            onDispatch = { /* gestione futura di eventi, per ora non servono */ },
            onClosed = {
                Log.i(TAG, "🔌 socket chiuso")
                mainHandler.post {
                    if (shouldStayConnected) scheduleReconnect(accessToken)
                }
            },
            onFailure = { t ->
                Log.w(TAG, "⚠️ gateway failure: ${t.message}")
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
        Log.i(TAG, "🔄 riconnessione tra ${delayMs}ms")
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
                Log.i(TAG, "🎬 player chiuso -> 🧹 presenza pulita")
                gateway?.sendPresence(PresenceBuilder.empty())
                lastSentKey = null
            }
            lastPlaying = null
            return
        }

        val player = PlayerMetaAccess.currentPlayer()
        if (player == null) {
            Log.i(TAG, "🎬 schermata player attiva ma player non trovato (currentPlayer=null)")
            return
        }
        val meta = PlayerMetaAccess.currentEpisode()
        val playing = runCatching { player.getIsPlaying() }.getOrDefault(false)
        val position = runCatching { player.getPosition() }.getOrNull() ?: 0L
        val duration = runCatching { player.getDuration() }.getOrNull()
        val now = System.currentTimeMillis()

        val metaChanged = meta != lastMeta
        if (metaChanged) lastMeta = meta

        val prevPlaying = lastPlaying
        lastPlaying = playing

        val effective = effectivePosition(metaChanged, position, duration, playing, now)
        val firstSend = lastSentKey == null

        if (playing) {
            // inviamo quando: primo tick, cambi meta, transizione pausa→play
            // oppure periodicamente (REFRESH_MS) per rimanere coerenti dopo i seek
            val needsFresh = firstSend || playingChanged(prevPlaying, playing) ||
                metaChanged || (now - lastPushMs > REFRESH_MS)
            if (needsFresh) {
                push(meta, effective, duration, playing)
                lastPushMs = now
            }
        } else {
            // pausa: rimuoviamo i timestamps ma lasciamo vedere cosa guardiamo
            if (firstSend || playingChanged(prevPlaying, playing) || metaChanged) {
                push(meta, effective, duration, playing)
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
        val duration = runCatching { player.getDuration() }.getOrNull()
        val meta = PlayerMetaAccess.currentEpisode()
        val effective = effectivePosition(meta != lastMeta, position, duration, playing, System.currentTimeMillis())
        push(meta, effective, duration, playing)
    }

    /**
     * Posizione "effettiva" da mostrare: se quella del player è plausibile (entro la
     * durata) la usiamo e risincronizziamo il base; altrimenti (valore spazzatura
     * tipo "ora corrente") contiamo l'elapsed con l'orologio del device così il
     * tempo non esplode in numeri assurdi tipo 495791 ore.
     */
    private fun effectivePosition(metaChanged: Boolean, position: Long, durationMs: Long?, playing: Boolean, now: Long): Long {
        if (metaChanged) {
            clockBaseMs = 0L
            elapsedBaseMs = 0L
            lastSanePositionMs = 0L
        }
        if (!playing) {
            clockBaseMs = 0L
            return position.takeIf { isSanePosition(it, durationMs) } ?: 0L
        }

        if (isSanePosition(position, durationMs)) {
            lastSanePositionMs = position
            elapsedBaseMs = position
            clockBaseMs = now
            return position
        }

        // posizione insana ma stiamo riproducendo: fallback a clock del device
        if (clockBaseMs == 0L) {
            clockBaseMs = now
            elapsedBaseMs = lastSanePositionMs
        }
        return elapsedBaseMs + (now - clockBaseMs)
    }

    /** true se la posizione è plausibile: entro la durata, o < 24h se durata assente. */
    private fun isSanePosition(position: Long, durationMs: Long?): Boolean {
        if (position < 0L) return false
        return if (durationMs != null && durationMs > 0L) position <= durationMs
        else position <= 86_400_000L
    }

    private fun push(meta: ResultEpisode?, position: Long, durationMs: Long?, playing: Boolean) {
        val g = gateway ?: return
        if (!g.isOpen) return

        val poster = PlayerMetaAccess.currentPoster()
        val activities = PresenceBuilder.buildActivity(
            PresenceBuilder.PlayerState(
                meta = meta,
                positionMs = position,
                isPlaying = playing,
                durationMs = durationMs,
                poster = poster,
                clockOffsetMs = g.serverOffsetMs,
            )
        )
        Log.i(TAG, "📤 INVIO presenza playing=$playing pos=${position}ms dur=${durationMs} meta=${meta?.headerName} poster=${poster ?: "<vuoto>"}")
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