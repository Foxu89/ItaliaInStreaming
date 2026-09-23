package it.dogior.hadEnough.watchparty

import android.util.Log

private const val TAG = "WatchParty"

/**
 * Astrazione del player usata da WatchPartyManager/Overlay/SettingsFragment.
 *
 * Esiste per non legare il resto del plugin al tipo IPlayer di CloudStream
 * (che su un host diverso da CloudStream potrebbe non esistere a runtime,
 * es. Nuvio Enhanced): tutti i metodi usano solo tipi primitivi.
 *
 * NON è API ufficiale/garantita in nessuno dei due host. Ogni implementazione
 * deve restare tollerante: in caso di dubbio ritornare null/false invece di
 * lanciare eccezioni, così il resto del plugin degrada invece di crashare.
 */
interface WatchPartyPlaybackBridge {
    /** True se in questo momento l'utente ha una schermata di riproduzione aperta. */
    fun isPlayerScreenActive(): Boolean

    /** Stato play/pausa corrente. Default false se sconosciuto. */
    fun getIsPlaying(): Boolean

    /** Posizione corrente in ms, o null se non disponibile. */
    fun getPosition(): Long?

    fun seekTo(positionMs: Long)
    fun play()
    fun pause()

    /**
     * Cambia episodio sul player locale. [localUserAction] distingue un tap
     * dell'utente su questo dispositivo (PlayerEventSource.UI nell'originale)
     * da un comando arrivato dalla stanza (PlayerEventSource.Sync) — la
     * stessa distinzione che il plugin faceva già prima di questo bridge.
     * Ritorna false se l'host non supporta questo comando (es. Nuvio, che
     * non espone un "prossimo episodio" tramite l'unico canale pubblico
     * disponibile, la MediaSession): il chiamante deve degradare senza
     * errori visibili all'utente.
     */
    fun nextEpisode(localUserAction: Boolean): Boolean

    /** Stringa breve di diagnostica per capire perché isPlayerScreenActive()
     *  ritorna true/false, da mostrare in un Toast quando non si ha accesso
     *  al logcat. Non usata in produzione, solo per debug. */
    fun debugSnapshot(): String
}

/**
 * Punto di ingresso unico usato dal resto del plugin. Rileva l'host UNA
 * SOLA VOLTA al primo utilizzo e da quel momento delega sempre alla stessa
 * implementazione (evita di rifare il rilevamento ad ogni chiamata).
 */
object WatchPartyPlayback : WatchPartyPlaybackBridge {

    private val delegate: WatchPartyPlaybackBridge by lazy { detectHostBridge() }

    /**
     * True se l'host rilevato è CloudStream (o un fork che ne porta le
     * classi ui.player.*), false altrimenti (presumibilmente Nuvio
     * Enhanced). Usata da WatchPartyPlugin per decidere se registrare il
     * provider "finto" richiesto solo dal loader di Nuvio.
     */
    val isCloudStreamHost: Boolean get() = delegate is CloudStreamPlaybackBridge

    /**
     * Rilevamento: se le classi UI-player di CloudStream (com.lagradost.
     * cloudstream3.ui.player.*) sono risolvibili a runtime, siamo dentro
     * l'app CloudStream vera (o un fork che le porta con sé) e usiamo il
     * bridge originale via PlayerAccess/IPlayer. Se NON lo sono (es. Nuvio
     * Enhanced, che non le include: vedi CLOUDSTREAM_CROSS_PLATFORM_
     * COMPATIBILITY.md e il runtime-api aar del suo repository), usiamo il
     * bridge basato su MediaSession/notifica.
     */
    private fun detectHostBridge(): WatchPartyPlaybackBridge {
        val isRealCloudStreamPlayer = runCatching {
            Class.forName("com.lagradost.cloudstream3.ui.player.IPlayer")
            true
        }.getOrDefault(false)

        return if (isRealCloudStreamPlayer) {
            Log.d(TAG, "🧭 WatchPartyPlayback: host CloudStream rilevato (IPlayer presente), uso CloudStreamPlaybackBridge")
            CloudStreamPlaybackBridge()
        } else {
            Log.d(TAG, "🧭 WatchPartyPlayback: IPlayer non risolvibile, presumo host Nuvio Enhanced, uso NuvioPlaybackBridge")
            NuvioPlaybackBridge()
        }
    }

    override fun isPlayerScreenActive(): Boolean = runCatching { delegate.isPlayerScreenActive() }.getOrDefault(false)
    override fun getIsPlaying(): Boolean = runCatching { delegate.getIsPlaying() }.getOrDefault(false)
    override fun getPosition(): Long? = runCatching { delegate.getPosition() }.getOrNull()
    override fun seekTo(positionMs: Long) { runCatching { delegate.seekTo(positionMs) } }
    override fun play() { runCatching { delegate.play() } }
    override fun pause() { runCatching { delegate.pause() } }
    override fun nextEpisode(localUserAction: Boolean): Boolean =
        runCatching { delegate.nextEpisode(localUserAction) }.getOrDefault(false)
    override fun debugSnapshot(): String =
        runCatching { delegate.debugSnapshot() }.getOrDefault("errore lettura snapshot")
}
