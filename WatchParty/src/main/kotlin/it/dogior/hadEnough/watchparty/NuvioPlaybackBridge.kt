package it.dogior.hadEnough.watchparty

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
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
 *  - funziona solo mentre la notifica è attiva, cioè durante la
 *    riproduzione vera e propria (non nella schermata dei dettagli prima
 *    di premere play) — comportamento accettabile per WatchParty, che ha
 *    senso solo durante la riproduzione.
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
        val activity = CommonActivity.activity ?: return null
        val notificationManager = runCatching {
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull() ?: return null

        val active = runCatching { notificationManager.activeNotifications }.getOrNull() ?: return null
        val statusBarNotification = active.firstOrNull { it.id == knownNotificationId }
            ?: active.firstOrNull { it.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true }
            ?: return null

        val token = runCatching {
            @Suppress("DEPRECATION")
            statusBarNotification.notification.extras
                ?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        }.getOrNull() ?: return null

        if (token != cachedToken || cachedController == null) {
            cachedController = runCatching { MediaController(activity, token) }.getOrNull()
            cachedToken = token
            if (cachedController != null) {
                Log.d(TAG, "🎬 NuvioPlaybackBridge: nuova MediaSession agganciata")
            }
        }
        return cachedController
    }

    override fun isPlayerScreenActive(): Boolean = controller() != null

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
}
