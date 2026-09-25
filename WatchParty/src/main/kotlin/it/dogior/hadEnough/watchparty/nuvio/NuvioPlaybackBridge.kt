package it.dogior.hadEnough.watchparty

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.lagradost.cloudstream3.CommonActivity
import java.lang.ref.WeakReference

/**
 * Bridge per Nuvio Enhanced (build "Android Full").
 *
 * FONTE PRIMARIA: androidx.media3.ui.PlayerView.
 *
 * Nuvio usa Media3/ExoPlayer per riprodurre (vedi PlayerEngine.android.kt
 * nel suo repository: androidx.media3.ui.PlayerView, androidx.media3.ui.
 * SubtitleView, ecc.). A differenza delle classi interne di Nuvio (che
 * l'R8 della build release rinomina, proguard-cloudstream-full.pro non le
 * protegge) o di Material Components (che Nuvio non usa affatto, essendo
 * un'app Compose/Material3), Media3 è una libreria AndroidX che Nuvio
 * porta davvero con sé nel suo processo — cercarla nell'albero delle view
 * dell'Activity corrente funziona senza passare da notifiche, permessi o
 * servizi in foreground (tutte cose che su alcuni dispositivi, es. MIUI,
 * possono fallire in silenzio: vedi i due fallback più sotto, tenuti solo
 * come ulteriore rete di sicurezza).
 *
 * PlayerView.getPlayer() restituisce direttamente l'androidx.media3.common.
 * Player reale (l'ExoPlayer in uso): play()/pause()/seekTo()/isPlaying/
 * currentPosition sono chiamate dirette sul player vero, non un proxy.
 *
 * Limiti noti:
 *  - nessun comando "prossimo episodio" (nextEpisode ritorna sempre false):
 *    Player non ha un concetto di "episodio", solo di traccia/posizione;
 *  - Nuvio ha ANCHE un motore alternativo basato su libmpv
 *    (NuvioLibmpvView, per contenuti che ExoPlayer non gestisce bene) che
 *    NON è una PlayerView: quando è quello in uso, questo bridge non trova
 *    nulla e cade sui due fallback sotto (notifica/keepScreenOn), che però
 *    finora si sono rivelati inaffidabili sul dispositivo di test — quindi
 *    con l'engine mpv l'icona potrebbe non apparire lo stesso. Da
 *    verificare quando càpita un contenuto che usa quell'engine.
 */
class NuvioPlaybackBridge : WatchPartyPlaybackBridge {

    // --- Fonte primaria: PlayerView nell'albero delle view -------------

    private var cachedPlayerViewRef: WeakReference<PlayerView>? = null

    private fun findPlayerView(): PlayerView? {
        cachedPlayerViewRef?.get()?.let { cached ->
            if (cached.isShown && cached.player != null) return cached
        }
        val activity = CommonActivity.activity ?: return null
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val found = runCatching { searchPlayerView(decor) }.getOrNull()
        cachedPlayerViewRef = found?.let { WeakReference(it) }
        return found
    }

    private fun searchPlayerView(view: View): PlayerView? {
        if (view is PlayerView && view.player != null && view.isShown) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                searchPlayerView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun player(): Player? = findPlayerView()?.player

    // --- Fallback secondario: MediaSession via notifica -----------------
    // Tenuto come rete di sicurezza per l'engine mpv (senza PlayerView) o
    // per versioni future di Nuvio. Sul dispositivo di test la notifica
    // "now playing" non parte mai (notif=0 nei log), quindi in pratica
    // oggi questo ramo non aiuta — ma non fa nemmeno danno, resta gratis.

    private val knownNotificationId = 0x4E55
    private var cachedToken: MediaSession.Token? = null
    private var cachedController: MediaController? = null

    private fun controller(): MediaController? {
        val activity = CommonActivity.activity ?: return null
        val notificationManager = runCatching {
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull() ?: return null
        val active = runCatching { notificationManager.activeNotifications }.getOrNull()
        if (active.isNullOrEmpty()) return null
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
        }
        return cachedController
    }

    // --- Fallback terziario: FLAG_KEEP_SCREEN_ON -------------------------
    // Solo per isPlayerScreenActive(): non dà comandi/posizione, solo
    // "sì/no un video sta giocando". Vedi debugSnapshot: sul dispositivo di
    // test è risultato false anche durante la riproduzione, quindi non è
    // affidabile quanto sperato — tenuto comunque, costa zero.

    private fun windowKeepsScreenOn(): Boolean = runCatching {
        val flags = CommonActivity.activity?.window?.attributes?.flags ?: return false
        (flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
    }.getOrDefault(false)

    // --- API pubblica ------------------------------------------------

    override fun isPlayerScreenActive(): Boolean =
        player() != null || controller() != null || windowKeepsScreenOn()

    override fun getIsPlaying(): Boolean {
        player()?.let { return it.isPlaying }
        val state = controller()?.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
    }

    override fun getPosition(): Long? {
        player()?.let { return it.currentPosition }
        return controller()?.playbackState?.position
    }

    override fun seekTo(positionMs: Long) {
        player()?.let { it.seekTo(positionMs); return }
        controller()?.transportControls?.seekTo(positionMs)
    }

    override fun play() {
        player()?.let { it.play(); return }
        controller()?.transportControls?.play()
    }

    override fun pause() {
        player()?.let { it.pause(); return }
        controller()?.transportControls?.pause()
    }

    /** Né Player né la MediaSession di Nuvio espongono un concetto di "episodio": non supportato. */
    override fun nextEpisode(localUserAction: Boolean): Boolean = false

    override fun debugSnapshot(): String {
        val activity = CommonActivity.activity ?: return "activity=NO"
        val hasPlayerView = player() != null
        val nm = runCatching {
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
        val notifCount = runCatching { nm?.activeNotifications?.size }.getOrNull() ?: -1
        val keepScreenOn = windowKeepsScreenOn()
        val hasController = controller() != null
        // Corto apposta: deve stare leggibile in un Toast.
        return "pv=$hasPlayerView notif=$notifCount kso=$keepScreenOn ctrl=$hasController active=${isPlayerScreenActive()}"
    }
}
