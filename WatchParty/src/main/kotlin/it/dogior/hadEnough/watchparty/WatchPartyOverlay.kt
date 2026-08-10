package it.dogior.hadEnough.watchparty

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
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
 *
 * Quando una stanza è attiva mostra anche una freccia a sinistra (centro
 * verticale) che apre un pannello di chat laterale fino a ~metà schermo.
 * Un pallino rosso sulla freccia avvisa di messaggi non letti.
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

    // --- chat ---
    private var chatArrowHost: FrameLayout? = null
    private var chatUnreadDot: View? = null
    private var chatRoot: FrameLayout? = null
    private var chatPanel: LinearLayout? = null
    private var chatMessages: LinearLayout? = null
    private var chatInput: EditText? = null
    private var chatPanelOpen = false

    private fun isButtonInvisible(): Boolean =
        CloudStreamApp.getKey<String>("wp_button_invisible") == "true"

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

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
        manager.onChatMessage = { sender, text -> handler.post { onChatReceived(sender, text) } }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        removeFab()
        removeSpinner()
        removeChat()
    }

    private fun sync() {
        val activity = CommonActivity.activity
        if (activity == null || activity.isFinishing) {
            removeFab()
            removeChat()
            return
        }
        val shouldShow = PlayerAccess.isPlayerScreenActive()

        // l'utente ha appena chiuso il player mentre la stanza era attiva: la chiudiamo
        if (wasPlayerActive && !shouldShow && manager.role != WatchPartyManager.Role.IDLE) {
            android.util.Log.d("WatchParty", "🚪 Player chiuso con stanza attiva, esco dalla stanza")
            manager.leaveRoom()
        }
        wasPlayerActive = shouldShow

        val inRoom = manager.role != WatchPartyManager.Role.IDLE

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

        // chat: visibile solo con stanza attiva E player aperto
        if (shouldShow && inRoom && (chatArrowHost == null || chatArrowHost!!.parent !== activity.window?.decorView)) {
            removeChat()
            addChat(activity)
        } else if ((!shouldShow || !inRoom) && chatArrowHost != null) {
            removeChat()
        }
    }

    // ---------------------------------------------------------------------
    // FAB
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Rotellina di attesa
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Chat
    // ---------------------------------------------------------------------

    private fun addChat(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        chatPanelOpen = false

        // ---- pomello/freccia a sinistra, centro verticale ----
        val host = FrameLayout(activity).apply { isClickable = true; isFocusable = true }
        val size = dp(activity, 40)
        val arrowImage = ImageView(activity).apply {
            setImageDrawable(getDrawable("chat_chevron") ?: getDrawable("watchparty_icon"))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(activity, 9), dp(activity, 9), dp(activity, 12), dp(activity, 9))
        }
        val hostParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            marginStart = dp(activity, 8)
        }
        // sfondo circolare semitrasparente
        host.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99000000.toInt())
        }
        host.setOnClickListener { openChat() }
        host.addView(arrowImage, FrameLayout.LayoutParams(size, size))

        val dot = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
            }
            visibility = View.GONE
        }
        val dotSize = dp(activity, 12)
        val dotParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
            gravity = Gravity.END or Gravity.TOP
        }
        host.addView(dot, dotParams)

        // ---- pannello laterale (nascosto a sinistra) + zona "tap fuori" ----
        val root = FrameLayout(activity).apply { visibility = View.GONE }

        val outsideCatcher = View(activity)
        root.addView(outsideCatcher, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val panel = run {
            val panelWidth = (activity.window?.decorView?.width ?: 0 * 1) * 0.48f
            val w = if (panelWidth > 0) panelWidth.toInt() else (activity.resources.displayMetrics.widthPixels * 0.48f).toInt()
            val p = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xF2121212.toInt())
                    cornerRadius = dp(activity, 12).toFloat()
                }
                setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            }

            // header "Chat"
            val header = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(activity).apply {
                text = "Chat"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val closeBtn = TextView(activity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(dp(activity, 8), dp(activity, 4), dp(activity, 4), dp(activity, 4))
                isClickable = true
                isFocusable = true
                setOnClickListener { closeChat() }
            }
            header.addView(title)
            header.addView(closeBtn)
            p.addView(header)

            // lista messaggi
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            val messages = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            scroll.addView(messages)
            p.addView(scroll)

            // riga input
            val inputRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(activity, 8), 0, 0)
            }
            val input = EditText(activity).apply {
                hint = "Scrivi un messaggio…"
                setTextColor(Color.WHITE)
                setHintTextColor(0xB3FFFFFF.toInt())
                setSingleLine(true)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            chatInput = input
            val sendBtn = ImageView(activity).apply {
                setImageDrawable(getDrawable("send_chevron"))
                isClickable = true
                isFocusable = true
                val s = dp(activity, 40)
                layoutParams = LinearLayout.LayoutParams(s, s)
                setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8))
                setOnClickListener { sendChat() }
            }
            inputRow.addView(input)
            inputRow.addView(sendBtn)
            p.addView(inputRow)

            root.addView(p, FrameLayout.LayoutParams(
                w,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START or Gravity.FILL_VERTICAL,
            ))
            p
        }

        outsideCatcher.setOnClickListener { closeChat() }

        runCatching { decor.addView(host, hostParams) }.onSuccess {
            chatArrowHost = host
            chatUnreadDot = dot
        }
        runCatching { decor.addView(root, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )) }.onSuccess {
            chatRoot = root
            chatPanel = panel
            val scroll = panel.getChildAt(1) as ScrollView
            val list = (panel.getChildAt(1) as ScrollView).getChildAt(0) as LinearLayout
            chatMessages = list
        }
    }

    private fun openChat() {
        val root = chatRoot ?: return
        val panel = chatPanel ?: return
        val host = chatArrowHost ?: return
        chatPanelOpen = true
        host.visibility = View.GONE // la freccia sparisce quando si apre
        root.visibility = View.VISIBLE
        chatUnreadDot?.visibility = View.GONE
        panel.visibility = View.VISIBLE
        panel.translationX = -panel.width.toFloat()
        panel.animate().translationX(0f).setDuration(220).start()
        scrollToBottom()
        requestShowKeyboard()
    }

    private fun closeChat() {
        val root = chatRoot ?: return
        val panel = chatPanel ?: return
        val host = chatArrowHost ?: return
        if (!chatPanelOpen) return
        chatPanelOpen = false
        panel.animate().translationX(-panel.width.toFloat()).setDuration(220)
            .withEndAction {
                root.visibility = View.GONE
                panel.visibility = View.GONE
                host.visibility = View.VISIBLE // riappare la freccia
            }.start()
    }

    private fun toggleChat() {
        if (chatPanelOpen) closeChat() else openChat()
    }

    private fun onChatReceived(sender: String, text: String) {
        val root = chatRoot ?: return
        if (chatPanelOpen && chatMessages != null) {
            appendLocalBubble(sender, text, mine = false)
        } else if (root.visibility == View.VISIBLE && chatMessages != null) {
            appendLocalBubble(sender, text, mine = false)
        } else {
            // pannello chiuso: segnala con il pallino rosso
            chatUnreadDot?.visibility = View.VISIBLE
        }
    }

    private fun sendChat() {
        val input = chatInput ?: return
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val me = manager.localDisplayName()
        val sent = manager.sendChatMessage(text)
        // l'host/gL'altro riceverà il testo; noi lo mostriamo in locale
        appendLocalBubble(me, text, mine = true)
        input.setText("")
    }

    private fun appendLocalBubble(sender: String, text: String, mine: Boolean) {
        val list = chatMessages ?: return
        val activity = CommonActivity.activity ?: return
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(0, dp(activity, 5), 0, dp(activity, 5))
        }
        val nameView = TextView(activity).apply {
            this.text = sender
            textSize = 11f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(dp(activity, 6), 0, dp(activity, 6), dp(activity, 2))
        }
        val bubble = TextView(activity).apply {
            this.text = text
            textSize = 14f
            setTextColor(Color.WHITE)
            maxWidth = (list.width * 0.78f).toInt().coerceAtLeast(dp(activity, 120))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 14).toFloat()
                if (mine) {
                    setColor(0xFF2E7DFF.toInt())
                } else {
                    setColor(0xFF37474F.toInt())
                }
            }
            setPadding(dp(activity, 10), dp(activity, 7), dp(activity, 10), dp(activity, 7))
        }
        row.addView(nameView)
        row.addView(bubble)
        list.addView(row)
        scrollToBottom()
    }

    private fun scrollToBottom() {
        val root = chatRoot ?: return
        val scroll = (root.getChildAt(1) as? ViewGroup)?.getChildAt(1) as? ScrollView ?: return
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun requestShowKeyboard() {
        val input = chatInput ?: return
        try {
            val imm = CommonActivity.activity?.getSystemService(Activity.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {
        }
    }

    private fun removeChat() {
        chatPanelOpen = false
        chatArrowHost?.let { (it.parent as? ViewGroup)?.removeView(it) }
        chatRoot?.let { (it.parent as? ViewGroup)?.removeView(it) }
        chatArrowHost = null
        chatUnreadDot = null
        chatRoot = null
        chatPanel = null
        chatMessages = null
        chatInput = null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}