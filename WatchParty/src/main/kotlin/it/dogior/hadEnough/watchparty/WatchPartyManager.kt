package it.dogior.hadEnough.watchparty

import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.IPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * Gestisce una Watch Party a 2 utenti su un canale WebSocket di relay.
 *
 * A differenza della versione "fork" (che agganciava un listener diretto
 * sul player interno), qui lo stato locale viene rilevato via POLLING
 * pubblico (player.getPosition()/getIsPlaying()), perché un plugin non può
 * registrarsi sull'event bus interno di PlayerView senza modificare l'app.
 *
 * Prevenzione loop: quando applichiamo un comando remoto (seekTo/handleEvent
 * con source = Sync) marchiamo `lastRemoteCommandMs`. Il polling ignora ogni
 * variazione di stato avvenuta entro ECHO_WINDOW_MS da quel momento, così
 * non la re-invia al peer.
 *
 * LIMITE NOTO: il cambio di episodio/sorgente non viene propagato
 * automaticamente (IPlayer non espone un metodo pubblico per caricare un
 * nuovo URL). Viene solo inviata una notifica "EPISODE_HINT" col titolo,
 * l'altro utente deve cambiare episodio manualmente.
 */
class WatchPartyManager {

    enum class Role { IDLE, HOST, GUEST }

    companion object {
        private const val POLL_INTERVAL_MS = 200L
        // Basta coprire il primo tick dopo aver applicato un comando remoto:
        // da lì in poi lastKnownPosition riflette già il nuovo valore, quindi
        // non serve una finestra lunga (era 900ms, bloccava click legittimi).
        private const val ECHO_WINDOW_MS = 500L
        private const val SEEK_JUMP_THRESHOLD_MS = 1200L
        private const val RESYNC_THRESHOLD_MS = 1500L
        private const val HEARTBEAT_INTERVAL_MS = 6000L

        /** Endpoint del server di relay. Vedi WatchPartyServer/ per l'implementazione di riferimento. */
        const val DEFAULT_RELAY_URL = "wss://watchparty-relay.diegon7771.workers.dev/room"
    }

    var role: Role = Role.IDLE
        private set
    var currentPin: String? = null
        private set

    var onStatusText: ((String) -> Unit)? = null
    var onPeerConnected: ((Boolean) -> Unit)? = null
    var onEpisodeHint: ((String) -> Unit)? = null

    val isConnected: Boolean get() = socket?.isOpen == true

    private var socket: WatchPartySocket? = null
    private var relayUrl: String = DEFAULT_RELAY_URL

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile private var lastRemoteCommandMs = 0L

    // ultimo stato locale noto, per rilevare le transizioni via polling
    private var lastKnownPlaying: Boolean? = null
    private var lastKnownPosition: Long = 0L

    fun createRoom(relayUrl: String = DEFAULT_RELAY_URL): String {
        this.relayUrl = relayUrl
        val pin = (100000..999999).random(Random(System.nanoTime())).toString()
        role = Role.HOST
        currentPin = pin
        connectSocket(pin)
        startPolling()
        startHeartbeat()
        return pin
    }

    fun joinRoom(pin: String, relayUrl: String = DEFAULT_RELAY_URL) {
        this.relayUrl = relayUrl
        role = Role.GUEST
        currentPin = pin
        connectSocket(pin)
        startPolling()
        startHeartbeat()
    }

    fun leaveRoom() {
        if (isConnected) socket?.send("LEAVE_ROOM")
        release()
    }

    fun release() {
        pollJob?.cancel()
        pollJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        socket?.close()
        socket = null
        role = Role.IDLE
        currentPin = null
        lastKnownPlaying = null
    }

    // ---------------------------------------------------------------------
    // Connessione
    // ---------------------------------------------------------------------

    private fun connectSocket(pin: String) {
        socket = WatchPartySocket(
            baseWsUrl = relayUrl,
            onOpen = {
                mainHandler.post {
                    onStatusText?.invoke("Connesso, in attesa dell'amico…")
                    onPeerConnected?.invoke(true)
                    if (role == Role.GUEST) socket?.send("SYNC_REQUEST")
                }
            },
            onMessage = { msg -> mainHandler.post { handleRemoteMessage(msg) } },
            onClosed = {
                mainHandler.post {
                    onStatusText?.invoke("Connessione chiusa")
                    onPeerConnected?.invoke(false)
                }
            },
            onFailure = { t ->
                mainHandler.post {
                    onStatusText?.invoke("Errore di connessione: ${t.message}")
                    onPeerConnected?.invoke(false)
                }
            },
        ).also { it.connect(pin) }
    }

    // ---------------------------------------------------------------------
    // Polling dello stato locale (sostituisce l'hook sul player)
    // ---------------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                withContext(Dispatchers.Main) { pollLocalPlayer() }
            }
        }
    }

    /** Correzione periodica leggera del drift, senza pausa forzata: solo se lo scarto è reale. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (role == Role.HOST) withContext(Dispatchers.Main) { sendSyncState() }
            }
        }
    }

    private fun pollLocalPlayer() {
        if (role == Role.IDLE || !isConnected) return
        val player = PlayerAccess.currentPlayer() ?: return

        val playing = player.getIsPlaying()
        val position = player.getPosition() ?: return

        // ignora SOLO il tick immediatamente successivo a un comando che abbiamo
        // applicato noi da remoto (evita di rimandarlo indietro come se fosse
        // un'azione dell'utente locale). Non blocca i tick successivi.
        val withinEchoWindow = System.currentTimeMillis() - lastRemoteCommandMs < ECHO_WINDOW_MS

        val prevPlaying = lastKnownPlaying
        val prevPosition = lastKnownPosition
        lastKnownPlaying = playing
        lastKnownPosition = position

        if (withinEchoWindow) return
        if (prevPlaying == null) return // primo tick, solo inizializza

        // seek: salto di posizione più grande di quanto ci si aspetterebbe dal solo
        // scorrere del tempo tra un tick e l'altro. Ogni tick è valutato in modo
        // indipendente: click ravvicinati ma su tick diversi vengono inviati tutti.
        val expectedDrift = POLL_INTERVAL_MS + 400L
        if (abs(position - prevPosition) > expectedDrift.coerceAtLeast(SEEK_JUMP_THRESHOLD_MS)) {
            socket?.send(WatchPartyMessage(type = "SEEK", position = position, playing = playing))
            return
        }

        // play/pausa
        if (playing != prevPlaying) {
            socket?.send(if (playing) "PLAY" else "PAUSE")
        }
    }

    // ---------------------------------------------------------------------
    // Messaggi in arrivo
    // ---------------------------------------------------------------------

    private fun handleRemoteMessage(msg: WatchPartyMessage) {
        when (msg.type) {
            "PEER_JOINED" -> {
                onStatusText?.invoke("Amico connesso!")
                onPeerConnected?.invoke(true)
                if (role == Role.HOST) sendSyncState()
            }

            "PEER_LEFT" -> {
                onStatusText?.invoke("L'amico ha lasciato la stanza")
                onPeerConnected?.invoke(false)
            }

            "SYNC_REQUEST" -> if (role == Role.HOST) sendSyncState()

            "SYNC_STATE" -> applyRemote {
                val player = PlayerAccess.currentPlayer() ?: return@applyRemote
                val current = player.getPosition() ?: 0L
                // heartbeat periodico: correggi solo se lo scarto è reale, niente
                // seek continui che darebbero fastidio durante la visione normale
                msg.position?.let {
                    if (abs(current - it) > RESYNC_THRESHOLD_MS) player.seekTo(it, PlayerEventSource.Sync)
                }
                if (msg.playing != null && msg.playing != player.getIsPlaying()) {
                    player.handleEvent(
                        if (msg.playing) CSPlayerEvent.Play else CSPlayerEvent.Pause,
                        PlayerEventSource.Sync
                    )
                }
            }

            "PLAY" -> applyRemote {
                PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync)
            }

            "PAUSE" -> applyRemote {
                PlayerAccess.currentPlayer()?.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync)
            }

            "SEEK" -> applyRemote {
                val player = PlayerAccess.currentPlayer() ?: return@applyRemote
                val pos = msg.position ?: return@applyRemote
                player.seekTo(pos, PlayerEventSource.Sync)
                // rispetta lo stato play/pausa che aveva chi ha fatto il seek,
                // niente più pausa forzata: era la causa principale della lentezza percepita
                if (msg.playing != null && msg.playing != player.getIsPlaying()) {
                    player.handleEvent(
                        if (msg.playing) CSPlayerEvent.Play else CSPlayerEvent.Pause,
                        PlayerEventSource.Sync
                    )
                }
            }

            "EPISODE_HINT" -> msg.title?.let { onEpisodeHint?.invoke(it) }

            "LEAVE_ROOM" -> {
                onStatusText?.invoke("L'amico ha lasciato la stanza")
                onPeerConnected?.invoke(false)
            }
        }
    }

    private fun sendSyncState() {
        val player = PlayerAccess.currentPlayer() ?: return
        socket?.send(
            WatchPartyMessage(
                type = "SYNC_STATE",
                position = player.getPosition() ?: 0L,
                playing = player.getIsPlaying(),
            )
        )
    }

    /** Notifica "morbida": l'host segnala un cambio episodio, il guest deve cambiarlo a mano. */
    fun notifyEpisodeChanged(title: String) {
        if (role != Role.HOST || !isConnected) return
        socket?.send(WatchPartyMessage(type = "EPISODE_HINT", title = title))
    }

    private fun applyRemote(block: () -> Unit) {
        lastRemoteCommandMs = System.currentTimeMillis()
        mainHandler.post(block)
    }
}
