package it.dogior.hadEnough.watchparty

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "WatchParty"

/**
 * Popup informativo mostrato una sola volta: spiega che i comandi di
 * riproduzione (play/pausa/posizione) passano attraverso un relay esterno
 * (il Cloudflare Worker) per essere inoltrati all'altro utente della stanza.
 *
 * NON usa più un Event dell'app (afterPluginsLoadedEvent) perché non è
 * garantito che scatti DOPO che il nostro Plugin.load() abbia già fatto
 * l'iscrizione — race condition possibile a freddo. Usa invece lo stesso
 * pattern di polling già collaudato in WatchPartyOverlay: molto più
 * deterministico e facile da diagnosticare via log.
 */
object WatchPartyConsent {

    private const val KEY_ACCEPTED = "wp_privacy_accepted"
    private const val KEY_ACCEPTED_AT = "wp_privacy_accepted_at"

    private var shownThisSession = false
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            showIfNeeded()
            handler.postDelayed(this, 1000L)
        }
    }

    fun hasAccepted(): Boolean = getKey<Boolean>(KEY_ACCEPTED) == true

    /** Data leggibile dell'accettazione, per mostrarla nelle impostazioni del plugin. */
    fun acceptedAtLabel(): String? {
        val millis = getKey<Long>(KEY_ACCEPTED_AT) ?: return null
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN)
        return fmt.format(Date(millis))
    }

    private fun setAccepted() {
        Log.d(TAG, "✅ WatchPartyConsent: utente ha accettato, salvo la preferenza")
        setKey(KEY_ACCEPTED, true)
        setKey(KEY_ACCEPTED_AT, System.currentTimeMillis())
        running = false
        handler.removeCallbacks(tick)
    }

    /** Chiamata una volta sola da WatchPartyPlugin.load(). */
    fun attach() {
        Log.d(TAG, "🚀 WatchPartyConsent.attach() chiamata da load() del plugin")
        if (hasAccepted()) {
            Log.d(TAG, "⏭️ WatchPartyConsent: già accettato in passato (${acceptedAtLabel()}), popup non necessario")
            return
        }
        if (running) return
        running = true
        Log.d(TAG, "⏱️ WatchPartyConsent: avvio il controllo periodico (ogni 1s) per mostrare il popup")
        handler.post(tick)
    }

    private fun showIfNeeded() {
        if (hasAccepted() || shownThisSession) {
            Log.d(TAG, "⏹️ WatchPartyConsent: fermo il controllo (accettato=${hasAccepted()}, mostrato=$shownThisSession)")
            running = false
            handler.removeCallbacks(tick)
            return
        }
        val activity = CommonActivity.activity
        if (activity == null) {
            Log.d(TAG, "⌛ WatchPartyConsent: CommonActivity.activity è ancora null, riprovo tra 1s")
            return
        }
        Log.d(TAG, "🎬 WatchPartyConsent: activity trovata (${activity::class.java.simpleName}), mostro il popup ORA")
        shownThisSession = true
        running = false
        handler.removeCallbacks(tick)
        try {
            show(activity)
        } catch (e: Exception) {
            Log.e(TAG, "💥 WatchPartyConsent: ECCEZIONE mentre costruivo il popup", e)
            shownThisSession = false // ritenta al prossimo giro se qualcosa è andato storto
        }
    }

    private fun show(context: Context) {
        val padding = (20 * context.resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(android.graphics.Color.parseColor("#1E1E1E")) // sfondo esplicito, non fidarti del tema
        }

        val messageView = TextView(context).apply {
            text = "Watch Party invia i comandi di riproduzione (play, pausa, " +
                "posizione, cambio episodio) a un server di relay esterno " +
                "(Cloudflare Worker), che li inoltra all'altro utente della " +
                "stanza. Non viene inviato l'audio/video, solo questi eventi " +
                "e il PIN della stanza. I messaggi non vengono salvati in modo " +
                "permanente sul server: servono solo a far arrivare i comandi " +
                "all'altro dispositivo in tempo reale."
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
        }

        val checkBox = CheckBox(context).apply {
            text = "Ho letto e accetto"
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, padding, 0, 0)
        }

        container.addView(messageView)
        container.addView(checkBox)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Prima di iniziare")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Accetto", null) // listener sotto, per poterlo disabilitare all'inizio
            .create()

        dialog.setOnShowListener {
            Log.d(TAG, "👀 WatchPartyConsent: popup effettivamente visibile a schermo (onShow)")
            val acceptBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            acceptBtn.isEnabled = false
            checkBox.setOnCheckedChangeListener { _, checked ->
                Log.d(TAG, "☑️ WatchPartyConsent: checkbox = $checked")
                acceptBtn.isEnabled = checked
            }
            acceptBtn.setOnClickListener {
                Log.d(TAG, "🖱️ WatchPartyConsent: pulsante Accetto premuto")
                setAccepted()
                dialog.dismiss()
            }
        }

        dialog.show()
        Log.d(TAG, "📤 WatchPartyConsent: dialog.show() chiamato")
    }
}
