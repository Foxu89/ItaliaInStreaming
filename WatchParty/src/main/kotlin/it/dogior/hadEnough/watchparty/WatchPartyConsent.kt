package it.dogior.hadEnough.watchparty

import android.app.AlertDialog
import android.content.Context
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Popup informativo mostrato una sola volta: spiega che i comandi di
 * riproduzione (play/pausa/posizione) passano attraverso un relay esterno
 * (il Cloudflare Worker) per essere inoltrati all'altro utente della stanza.
 *
 * Nessun pulsante "rifiuta" di proposito: creare una stanza è un'azione
 * esplicita dell'utente (serve comunque premere "Crea stanza"/"Unisciti"),
 * quindi non c'è un percorso alternativo da offrire — il popup è solo
 * trasparenza su un flusso che l'utente sta comunque per usare.
 */
object WatchPartyConsent {

    private const val KEY_ACCEPTED = "wp_privacy_accepted"
    private const val KEY_ACCEPTED_AT = "wp_privacy_accepted_at"

    private var shownThisSession = false

    fun hasAccepted(): Boolean = getKey<Boolean>(KEY_ACCEPTED) == true

    /** Data leggibile dell'accettazione, per mostrarla nelle impostazioni del plugin. */
    fun acceptedAtLabel(): String? {
        val millis = getKey<Long>(KEY_ACCEPTED_AT) ?: return null
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN)
        return fmt.format(Date(millis))
    }

    private fun setAccepted() {
        setKey(KEY_ACCEPTED, true)
        setKey(KEY_ACCEPTED_AT, System.currentTimeMillis())
    }

    /** Registrata una volta sola in WatchPartyPlugin.load(). */
    fun attach() {
        // reloadHomeEvent scatta SOLO quando l'utente cambia account e la home
        // page è diversa dalla precedente — praticamente mai. afterPluginsLoadedEvent
        // invece è invocato in modo affidabile da PluginManager ad ogni avvio
        // dell'app (e ad ogni reload manuale dei plugin dalle Impostazioni).
        MainActivity.afterPluginsLoadedEvent += { showIfNeeded() }
    }

    private fun showIfNeeded() {
        if (hasAccepted() || shownThisSession) return
        val activity = com.lagradost.cloudstream3.CommonActivity.activity ?: return
        shownThisSession = true
        show(activity)
    }

    private fun show(context: Context) {
        val padding = (20 * context.resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
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
        }

        val checkBox = CheckBox(context).apply {
            text = "Ho letto e accetto"
            setPadding(0, padding, 0, 0)
        }

        container.addView(messageView)
        container.addView(checkBox)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Prima di iniziare")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Accetto", null) // listener sotto, per poterlo disabilitare
            .create()

        dialog.setOnShowListener {
            val acceptBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            acceptBtn.isEnabled = false
            checkBox.setOnCheckedChangeListener { _, checked -> acceptBtn.isEnabled = checked }
            acceptBtn.setOnClickListener {
                setAccepted()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}
