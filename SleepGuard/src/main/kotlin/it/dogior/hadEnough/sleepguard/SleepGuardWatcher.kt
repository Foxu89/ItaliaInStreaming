package it.dogior.hadEnough.sleepguard

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.CSPlayerEvent
import com.lagradost.cloudstream3.ui.player.FullScreenPlayer
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.PlayerEventSource

private const val TAG = "SleepGuardWatcher"
private const val TICK_MS = 1000L

/**
 * Motore del plugin: un unico "heartbeat" al secondo (approccio a polling,
 * non ci sono hook ufficiali per player/episodio/tasti lato plugin — vedi
 * i commenti sotto per ogni limite) che:
 *  1. rileva quando è aperto un player (GeneratorPlayer, la sottoclasse
 *     pubblica di FullScreenPlayer usata per la riproduzione normale —
 *     stesso identico meccanismo di ricerca già usato e verificato in
 *     WatchParty, vedi currentPlayerFragment());
 *  2. aggancia un overlay trasparente per resettare il timer sul tocco;
 *  3. (opzionale) aggancia i tasti volume/telecomando, incatenando il
 *     listener già presente invece di sovrascriverlo — CommonActivity
 *     espone un solo slot condiviso con il player stesso per i suoi gesti
 *     volume/DPAD, quindi va trattato con cautela;
 *  4. (opzionale, BETA) legge il titolo mostrato a schermo per intuire un
 *     cambio episodio da Auto-Next — nessuna API ufficiale per questo,
 *     è una lettura best-effort della UI che può smettere di funzionare
 *     se CloudStream cambia gli id delle view del player;
 *  5. a soglia raggiunta, mette in pausa (via la proprietà pubblica
 *     `player` di AbstractPlayerFragment) e mostra il popup.
 */
object SleepGuardWatcher {

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private var hostPlugin: SleepGuardPlugin? = null

    private var currentFragment: FullScreenPlayer? = null
    private var overlayView: View? = null

    private var lastInteractionAt = 0L
    private var episodesSinceInteraction = 0
    private var lastSeenTitle: String? = null
    private var hostTitleViewId: Int = 0
    private var hostTitleViewIdResolved = false

    private var ourKeyListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null
    private var originalKeyListener: ((Pair<KeyEvent?, Boolean>) -> Boolean)? = null

    private var popupShowing = false

    fun start(plugin: SleepGuardPlugin) {
        hostPlugin = plugin
        if (started) return
        started = true
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            runCatching { heartbeat() }.onFailure { Log.e(TAG, "heartbeat() fallito", it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun heartbeat() {
        val fragment = currentPlayerFragment()

        if (fragment == null) {
            detach()
            return
        }

        if (fragment !== currentFragment) {
            attach(fragment)
        }

        if (popupShowing) return

        val ctx = fragment.requireContext()

        if (SleepGuardPrefs.isVolumeKeyResetEnabled(ctx)) {
            ensureKeyListenerChained()
        } else {
            restoreKeyListenerIfOurs()
        }

        if (SleepGuardPrefs.isEpisodeCounterEnabled(ctx)) {
            pollEpisodeTitle(fragment)
        }

        val elapsedMinutes = (System.currentTimeMillis() - lastInteractionAt) / 60_000.0
        val timeTriggered = elapsedMinutes >= SleepGuardPrefs.inactivityMinutes(ctx)
        val episodeTriggered = SleepGuardPrefs.isEpisodeCounterEnabled(ctx) &&
            episodesSinceInteraction >= SleepGuardPrefs.episodeThreshold(ctx)

        if (timeTriggered || episodeTriggered) {
            triggerPopup(fragment)
        }
    }

    /**
     * Stesso identico meccanismo di PlayerAccess.kt in WatchParty (già in
     * produzione, confermato funzionante): CloudStream apre il player dentro
     * un NavHostFragment (navigazione interna via Navigation Component), non
     * come fragment di primo livello dell'Activity. Il tentativo precedente
     * (ricerca ricorsiva generica su tutti i childFragmentManager, cercando
     * una FullScreenPlayer resumed qualunque) trovava un fragment ma non
     * sempre quello giusto/aggiornato: risultato, il popup compariva ma
     * pausa/ripresa non avevano effetto sul player realmente in riproduzione.
     * Qui invece si va dritti al nav_host_fragment via il suo id reale e si
     * prende l'ultimo fragment del suo childFragmentManager, esattamente
     * come fa WatchParty.
     */
    private fun currentPlayerFragment(): GeneratorPlayer? = runCatching {
        val activity = CommonActivity.activity as? MainActivity ?: return@runCatching null
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as? NavHostFragment ?: return@runCatching null
        val top: Fragment? = navHost.childFragmentManager.fragments.lastOrNull()
        top as? GeneratorPlayer
    }.getOrNull()

    private fun attach(fragment: FullScreenPlayer) {
        detach() // chiude/scollega eventuale sessione precedente rimasta appesa
        currentFragment = fragment
        lastInteractionAt = System.currentTimeMillis()
        episodesSinceInteraction = 0
        lastSeenTitle = null
        attachTouchOverlay(fragment)
    }

    private fun detach() {
        if (currentFragment == null) return
        removeTouchOverlay()
        restoreKeyListenerIfOurs()
        currentFragment = null
        popupShowing = false
    }

    /**
     * Overlay trasparente sopra la view del player: intercetta ACTION_DOWN
     * per resettare il timer ma ritorna sempre false, così il tocco prosegue
     * normalmente verso i controlli del player sotto (passthrough, non lo consuma).
     */
    private fun attachTouchOverlay(fragment: FullScreenPlayer) {
        val root = fragment.view as? ViewGroup ?: return
        val overlay = FrameLayout(root.context).apply {
            isClickable = false
            isFocusable = false
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    lastInteractionAt = System.currentTimeMillis()
                }
                false
            }
        }
        root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlayView = overlay
    }

    private fun removeTouchOverlay() {
        val overlay = overlayView ?: return
        runCatching { (overlay.parent as? ViewGroup)?.removeView(overlay) }
        overlayView = null
    }

    /**
     * CommonActivity.keyEventListener è UNA variabile sola, non una lista:
     * il player (PlayerGestureHelper/FullScreenPlayer) la usa per i propri
     * gesti volume/DPAD e può riassegnarla in ogni momento del suo ciclo
     * vita. Per non romperla: salviamo quello che c'è già, lo richiamiamo
     * sempre noi stessi, e ricontrolliamo ad ogni tick se qualcun altro
     * (il player) l'ha sovrascritta nel frattempo, riagganciandoci sopra.
     */
    private fun ensureKeyListenerChained() {
        if (CommonActivity.keyEventListener === ourKeyListener) return
        originalKeyListener = CommonActivity.keyEventListener
        val wrapper: (Pair<KeyEvent?, Boolean>) -> Boolean = { pair ->
            val (event, _) = pair
            if (event != null && isRelevantKey(event.keyCode)) {
                lastInteractionAt = System.currentTimeMillis()
            }
            // Inoltra sempre al listener originale: non cambiamo il comportamento
            // nativo, aggiungiamo solo il reset del timer in ascolto.
            originalKeyListener?.invoke(pair) ?: false
        }
        ourKeyListener = wrapper
        CommonActivity.keyEventListener = wrapper
    }

    private fun restoreKeyListenerIfOurs() {
        if (CommonActivity.keyEventListener === ourKeyListener) {
            CommonActivity.keyEventListener = originalKeyListener
        }
        ourKeyListener = null
        originalKeyListener = null
    }

    private fun isRelevantKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_BACK -> true
        else -> false
    }

    /**
     * BETA: nessuna API per sapere quando Auto-Next passa all'episodio
     * successivo (lo stato interno del player è privato). Come proxy si
     * legge il testo del titolo mostrato a schermo (id reale del player
     * di CloudStream, "player_video_title") e si conta un cambio di
     * episodio quando il testo cambia. Se CloudStream rinomina quella
     * view in un futuro aggiornamento questa funzione smette silenziosamente
     * di contare (fallisce dentro il runCatching del chiamante), senza
     * rompere il resto del plugin.
     */
    private fun pollEpisodeTitle(fragment: FullScreenPlayer) {
        val root = fragment.view ?: return
        if (!hostTitleViewIdResolved) {
            hostTitleViewId = runCatching {
                fragment.requireContext().resources.getIdentifier(
                    "player_video_title", "id", fragment.requireContext().packageName
                )
            }.getOrDefault(0)
            hostTitleViewIdResolved = true
        }
        if (hostTitleViewId == 0) return
        val titleView = root.findViewById<TextView>(hostTitleViewId) ?: return
        val text = titleView.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (lastSeenTitle == null) {
            lastSeenTitle = text
            return
        }
        if (text != lastSeenTitle) {
            lastSeenTitle = text
            episodesSinceInteraction++
        }
    }

    private fun triggerPopup(fragment: FullScreenPlayer) {
        if (popupShowing) return
        val plugin = hostPlugin ?: return
        popupShowing = true

        // PlayerEventSource.Sync (non UI): stessa scelta di WatchParty, è la
        // fonte pensata per un comando di pausa/ripresa che arriva da fuori
        // dai controlli a schermo, non da un tap dell'utente sul player.
        runCatching { fragment.player.handleEvent(CSPlayerEvent.Pause, PlayerEventSource.Sync) }
            .onFailure { Log.e(TAG, "Impossibile mettere in pausa il player", it) }

        val popup = SleepGuardPopupFragment(
            plugin = plugin,
            onYes = {
                popupShowing = false
                lastInteractionAt = System.currentTimeMillis()
                episodesSinceInteraction = 0
                lastSeenTitle = null
                runCatching { fragment.player.handleEvent(CSPlayerEvent.Play, PlayerEventSource.Sync) }
                    .onFailure { Log.e(TAG, "Impossibile riprendere il player", it) }
            },
            onNo = {
                // Il player vive dentro la stessa Activity di tutta l'app
                // (navigazione interna via NavHostFragment, non una Activity
                // separata): finish() chiude quindi l'intera app, non solo
                // il player. Comportamento accettato così su richiesta,
                // niente da "aggiustare" qui per ora.
                popupShowing = false
                runCatching {
                    val activity = fragment.activity
                    activity?.finish()
                }.onFailure {
                    Log.w(TAG, "finish() dell'activity non riuscita, resto in pausa", it)
                }
            },
        )

        runCatching {
            popup.show(fragment.parentFragmentManager, "SleepGuardPopup")
        }.onFailure {
            Log.e(TAG, "Impossibile mostrare il popup SleepGuard", it)
            popupShowing = false
        }
    }
}
