package it.dogior.hadEnough.watchparty

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import android.view.WindowManager
import com.lagradost.cloudstream3.CommonActivity

private const val TAG = "WatchParty"

/**
 * Bridge per Nuvio Enhanced (build "Android Full").
 *
 * Nuvio non esegue le classi ui.player.* di CloudStream (non sono presenti
 * a runtime: vedi CLOUDSTREAM_CROSS_PLATFORM_COMPATIBILITY.md e il runtime-
 * api aar del suo repository) e non offre alcun hook diretto sul player
 * verso i plugin caricati. Espone però, mentre un contenuto è in
 * riproduzione, una notifica "now playing" reale con una
 * android.media.session.MediaSession valida (vedi PlayerNowPlayingService.
 * android.kt / PlayerNowPlayingController.android.kt nel suo repository),
 * disponibile SOLO sulla build Android Full (non Play Store) e SOLO mentre
 * un contenuto è effettivamente caricato.
 *
 * Poiché il codice di questo plugin gira nello stesso processo/UID di
 * Nuvio (caricato via PathClassLoader nel suo stesso processo, non come
 * app separata), NotificationManager.getActiveNotifications() può leggere
 * le notifiche che Nuvio stesso ha pubblicato, incluso l'extra
 * EXTRA_MEDIA_SESSION con il token — senza bisogno del permesso speciale
 * di "notification listener" (quel permesso serve solo per leggere le
 * notifiche di ALTRE app).
 *
 * Limiti noti, per design, rispetto al bridge CloudStream:
 *  - nessun comando "prossimo episodio" (nextEpisode ritorna sempre false):
 *    la MediaSession espone solo play/pause/seekTo, non cambio episodio;
 *  - nessuna informazione su QUALE contenuto è caricato (titolo/episodio):
 *    non necessaria per la sola sincronizzazione play/pausa/posizione;
 *  - i comandi (seekTo/play/pause) e la lettura di posizione/stato
 *    funzionano solo quando la notifica "now playing" è raggiungibile
 *    (vedi controller() sotto). Se il servizio in foreground di Nuvio non
 *    parte per qualche motivo (restrizioni del produttore del telefono
 *    tipo MIUI, permesso notifiche negato, ecc.), questi continuano a
 *    restituire null/false senza crashare, ma la sincronizzazione vera e
 *    propria non funziona finché quel servizio non parte.
 *  - isPlayerScreenActive() invece NON dipende solo dalla notifica: ha un
 *    fallback (vedi windowKeepsScreenOn() sotto) che fa apparire comunque
 *    l'icona quando un video è in riproduzione, anche se la notifica non
 *    parte — così l'utente può almeno aprire il menu, anche se poi la
 *    sincronizzazione stessa resta a posto solo quando la notifica
 *    funziona davvero.
 */
class NuvioPlaybackBridge : WatchPartyPlaybackBridge {

    // Id fisso della notifica "now playing" di Nuvio (0x4E55), letto dal suo
    // codice sorgente (PlayerNowPlayingService.android.kt). Usato come primo
    // tentativo, con un fallback più permissivo (qualunque notifica di
    // sistema con l'extra EXTRA_MEDIA_SESSION) nel caso l'id cambi in una
    // versione futura di Nuvio.
    private val knownNotificationId = 0x4E55

    private var cachedToken: MediaSession.Token? = null
    private var cachedController: MediaController? = null

    private fun controller(): MediaController? {
        val activity = CommonActivity.activity
        if (activity == null) {
            Log.d(TAG, "🔍 NuvioPlaybackBridge: CommonActivity.activity è null (host non ha ancora agganciato l'Activity)")
            return null
        }
        val notificationManager = runCatching {
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
        if (notificationManager == null) {
            Log.d(TAG, "🔍 NuvioPlaybackBridge: NotificationManager non ottenibile")
            return null
        }

        val active = runCatching { notificationManager.activeNotifications }.getOrNull()
        if (active == null) {
            Log.d(TAG, "🔍 NuvioPlaybackBridge: getActiveNotifications() ha lanciato un'eccezione")
            return null
        }
        if (active.isEmpty()) {
            Log.d(TAG, "🔍 NuvioPlaybackBridge: nessuna notifica attiva dell'app in questo momento (probabile: nessuna riproduzione in corso, oppure permesso notifiche non concesso su Android 13+)")
            return null
        }
        val statusBarNotification = active.firstOrNull { it.id == knownNotificationId }
            ?: active.firstOrNull { it.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true }
        if (statusBarNotification == null) {
            Log.d(TAG, "🔍 NuvioPlaybackBridge: ${active.size} notifiche attive ma nessuna è quella \"now playing\" (id atteso 0x${knownNotificationId.toString(16)}, id trovati: ${active.joinToString { "0x" + it.id.toString(16) }})")
            return null
        }

        val token = runCatching {
            @Suppress("DEPRECATION")
            statusBarNotification.notification.extras
                ?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        }.getOrNull()
        if (token == null) {
            Log.d(TAG, "🔍 NuvioPlaybackBridge: notifica \"now playing\" trovata ma senza EXTRA_MEDIA_SESSION valido")
            return null
        }

        if (token != cachedToken || cachedController == null) {
            cachedController = runCatching { MediaController(activity, token) }.getOrNull()
            cachedToken = token
            if (cachedController != null) {
                Log.d(TAG, "🎬 NuvioPlaybackBridge: nuova MediaSession agganciata")
            }
        }
        return cachedController
    }

    override fun isPlayerScreenActive(): Boolean {
        if (controller() != null) return true
        return windowKeepsScreenOn()
    }

    /**
     * Fallback che non passa dalla notifica: il player di Nuvio imposta
     * View.keepScreenOn = true sulla view video mentre un contenuto è in
     * riproduzione o in caricamento (PlayerEngine.android.kt). Questo
     * propaga sempre WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON sulla
     * Window dell'Activity — è comportamento standard della piattaforma
     * Android (ViewRootImpl), non passa da notifiche/servizi in
     * foreground/permessi, quindi non è soggetto alle restrizioni che
     * bloccano quelli su alcuni produttori (es. MIUI) né al permesso
     * notifiche. Unico limite: resta true solo mentre il video è in
     * riproduzione/caricamento, torna false in pausa — l'icona potrebbe
     * quindi sparire quando metti in pausa se la notifica non funziona.
     */
    private fun windowKeepsScreenOn(): Boolean {
        val active = runCatching {
            val flags = CommonActivity.activity?.window?.attributes?.flags ?: return false
            (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        }.getOrDefault(false)
        if (active) {
            Log.d(TAG, "🔍 NuvioPlaybackBridge: notifica non trovata ma FLAG_KEEP_SCREEN_ON attivo, mostro comunque l'icona")
        }
        return active
    }

    override fun getIsPlaying(): Boolean {
        val state = controller()?.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    override fun getPosition(): Long? = controller()?.playbackState?.position

    override fun seekTo(positionMs: Long) {
        controller()?.transportControls?.seekTo(positionMs)
    }

    override fun play() {
        controller()?.transportControls?.play()
    }

    override fun pause() {
        controller()?.transportControls?.pause()
    }

    /** Nuvio non espone un comando "prossimo episodio" via MediaSession: non supportato. */
    override fun nextEpisode(localUserAction: Boolean): Boolean = false

    override fun debugSnapshot(): String {
        val activity = CommonActivity.activity ?: return "activity=NO"

        val nm = runCatching {
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
        val notifCount = runCatching { nm?.activeNotifications?.size }.getOrNull() ?: -1
        val keepScreenOn = windowKeepsScreenOn()
        val hasController = controller() != null

        // Corto apposta: deve stare leggibile in un Toast.
        return "notif=$notifCount kso=$keepScreenOn ctrl=$hasController active=${isPlayerScreenActive()}"
    }
}
