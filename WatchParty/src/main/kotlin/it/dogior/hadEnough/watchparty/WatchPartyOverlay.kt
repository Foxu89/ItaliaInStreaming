package it.dogior.hadEnough.watchparty

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig

/**
 * Aggiunge un piccolo FAB sopra il decorView dell'activity, visibile solo
 * mentre la schermata del player è aperta. Non tocca il layout XML del
 * player (che è interno all'app): si limita ad appoggiarsi sopra, come
 * farebbe una libreria di overlay/tutorial.
 *
 * Il controllo "sono nella schermata player?" avviene via polling
 * (PlayerAccess.isPlayerScreenActive) perché non esiste un evento pubblico
 * per l'apertura/chiusura del player. Lo stesso polling rileva anche
 * quando l'utente ESCE dal player con una stanza attiva, per chiuderla.
 */
class WatchPartyOverlay(
    private val plugin: Plugin,
    private val manager: WatchPartyManager,
    private val onClick: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var fab: FloatingActionButton? = null
    private var spinner: ProgressBar? = null
    private var attachedActivity: Activity? = null
    private var running = false
    private var wasPlayerActive = false

    private fun isButtonInvisible(): Boolean =
        CloudStreamApp.getKey<String>("wp_button_invisible") == "true"

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            sync()
            handler.postDelayed(this, 700L)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
        manager.onBufferingGateChanged = { show -> handler.post { setSpinnerVisible(show) } }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        removeFab()
        removeSpinner()
    }

    private fun sync() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing) {
            removeFab()
            return
        }
        val shouldShow = PlayerAccess.isPlayerScreenActive()

        // l'utente ha appena chiuso il player mentre la stanza era attiva: la chiudiamo
        if (wasPlayerActive && !shouldShow && manager.role != WatchPartyManager.Role.IDLE) {
            android.util.Log.d("WatchParty", "🚪 Player chiuso con stanza attiva, esco dalla stanza")
            manager.leaveRoom()
        }
        wasPlayerActive = shouldShow

        if (shouldShow && (fab == null || attachedActivity !== activity)) {
            android.util.Log.d("WatchParty", "➕ WatchPartyOverlay: schermata player rilevata, aggiungo il FAB")
            removeFab()
            addFab(activity)
        } else if (!shouldShow && fab != null) {
            android.util.Log.d("WatchParty", "➖ WatchPartyOverlay: schermata player chiusa, rimuovo il FAB")
            removeFab()
            removeSpinner()
        } else if (fab != null) {
            fab?.let { updateVisibility(it) }
        }
    }

    private fun addFab(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val iconDrawable = runCatching {
            val res = plugin.resources ?: return@runCatching null
            val id = res.getIdentifier("watchparty_icon", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
            if (id != 0) res.getDrawable(id, null) else null
        }.getOrNull()
        val button = FloatingActionButton(activity).apply {
            if (iconDrawable != null) setImageDrawable(iconDrawable)
            else setImageResource(android.R.drawable.ic_menu_share)
            setOnClickListener { onClick() }
        }
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginEnd = dp(activity, 20)
            bottomMargin = dp(activity, 90) // sopra la barra di controllo del player
        }
        runCatching { decor.addView(button, params) }.onSuccess {
            fab = button
            attachedActivity = activity
            updateVisibility(button)
        }
    }

    private fun updateVisibility(button: FloatingActionButton) {
        val invisible = isButtonInvisible()
        if (invisible) {
            android.util.Log.d("WatchParty", "🙈 WatchPartyOverlay: pulsante impostato INVISIBILE (wp_button_invisible=true) — resta cliccabile ma non si vede")
        }
        button.alpha = if (invisible) 0f else 1f
    }

    private fun removeFab() {
        val button = fab ?: return
        val parent = button.parent as? ViewGroup
        runCatching { parent?.removeView(button) }
        fab = null
        attachedActivity = null
    }

    private fun setSpinnerVisible(show: Boolean) {
        val activity = CommonActivity.activity ?: return
        if (show) {
            if (spinner != null) return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val bar = ProgressBar(activity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF4FC3F7.toInt())
            }
            val size = dp(activity, 42)
            // mini-rotellina leggermente SOPRA quella nativa del player (che
            // è al centro): non si sovrappongono più, e il colore azzurro la
            // distingue come "attesa del plugin".
            val height = decor.height
            val params = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (height / 2) + dp(activity, 24)
            }
            runCatching { decor.addView(bar, params) }.onSuccess { spinner = bar }
        } else {
            removeSpinner()
        }
    }

    private fun removeSpinner() {
        val bar = spinner ?: return
        val parent = bar.parent as? ViewGroup
        runCatching { parent?.removeView(bar) }
        spinner = null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
