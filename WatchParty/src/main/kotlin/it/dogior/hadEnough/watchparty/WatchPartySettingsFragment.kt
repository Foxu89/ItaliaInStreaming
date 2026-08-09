package it.dogior.hadEnough.watchparty

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig // namespace del modulo, vedi build.gradle.kts root

private const val TAG = "WatchParty"

class WatchPartySettingsFragment(
    private val plugin: Plugin,
    private val manager: WatchPartyManager,
) : BottomSheetDialogFragment() {

    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    /** Stesso pattern di StreamITA: risoluzione a runtime dei drawable del plugin per nome. */
    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    private fun View.applyOutlineBackground() {
        background = getDrawable("outline")
    }

    private fun View.applyBlueBackground() {
        background = getDrawable("outline_blue")
    }

    private fun View.applyGreenBackground() {
        background = getDrawable("outline_green")
    }

    private fun View.applyDangerBackground() {
        background = getDrawable("outline_danger")
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        android.util.Log.d(TAG, "📄 onCreateView() inizio")

        val root = getLayout("watchparty_settings", inflater, container)

        val statusCard = root.findView<View>("wp_status_card")
        val status = root.findView<TextView>("wp_status")
        val statusDot = root.findView<View>("wp_status_dot")
        val participantsView = root.findView<TextView>("wp_participants")

        val pinCard = root.findView<View>("wp_pin_card")
        val pinDisplay = root.findView<TextView>("wp_pin_display")
        val copyPinBtn = root.findView<ImageButton>("wp_copy_pin")

        val joinCard = root.findView<View>("wp_join_card")
        val createBtn = root.findView<Button>("wp_create")
        val pinInput = root.findView<EditText>("wp_pin_input")
        val joinBtn = root.findView<Button>("wp_join")

        val activeRoomCard = root.findView<View>("wp_active_room_card")
        val leaveBtn = root.findView<Button>("wp_leave")
        val resyncBtn = root.findView<Button>("wp_resync")

        val settingsCard = root.findView<View>("wp_settings_card")
        val settingsHeader = root.findView<View>("wp_settings_header")
        val settingsArrow = root.findView<ImageView>("wp_settings_arrow")
        val settingsBody = root.findView<View>("wp_settings_body")
        val invisibleSwitch = root.findView<Switch>("wp_invisible_button")

        // --- Stile "card" identico a StreamITA, pulsanti principali in blu CloudStream ---
        statusCard.applyOutlineBackground()
        pinCard.applyOutlineBackground()
        joinCard.applyOutlineBackground()
        activeRoomCard.applyOutlineBackground()
        settingsCard.applyOutlineBackground()
        createBtn.applyBlueBackground()
        joinBtn.applyBlueBackground()
        resyncBtn.applyBlueBackground()
        leaveBtn.applyDangerBackground()   // azione distruttiva, resta rossa
        copyPinBtn.applyOutlineBackground()
        copyPinBtn.setImageDrawable(getDrawable("copy_icon"))

        // --- Sezione "Impostazioni" collassabile ---
        settingsHeader.setOnClickListener {
            val expanding = settingsBody.visibility != View.VISIBLE
            settingsBody.visibility = if (expanding) View.VISIBLE else View.GONE
            settingsArrow.animate().rotation(if (expanding) 180f else 0f).setDuration(150).start()
        }

        invisibleSwitch.isChecked = CloudStreamApp.getKey<String>("wp_button_invisible") == "true"
        invisibleSwitch.setOnCheckedChangeListener { _, checked ->
            CloudStreamApp.setKey("wp_button_invisible", if (checked) "true" else "false")
        }

        // --- Lista partecipanti: "Nome (Host, Tu)" / "Nome (Tu)" / "Nome (Host)" ecc. ---
        fun refreshParticipants() {
            if (manager.role == WatchPartyManager.Role.IDLE) {
                participantsView.visibility = View.GONE
                return
            }
            val me = manager.localDisplayName()
            val meLabel = if (manager.role == WatchPartyManager.Role.HOST) "$me (Host, Tu)" else "$me (Tu)"
            val peerName = manager.remotePeerName
            val lines = mutableListOf(meLabel)
            lines.add(
                if (peerName != null) {
                    if (manager.role == WatchPartyManager.Role.HOST) peerName else "$peerName (Host)"
                } else "In attesa di un partecipante…"
            )
            participantsView.visibility = View.VISIBLE
            participantsView.text = lines.joinToString("\n")
        }

        fun refreshUiForActiveRoom() {
            val inRoom = manager.role != WatchPartyManager.Role.IDLE
            joinCard.visibility = if (inRoom) View.GONE else View.VISIBLE
            activeRoomCard.visibility = if (inRoom) View.VISIBLE else View.GONE
            pinCard.visibility = if (inRoom && manager.role == WatchPartyManager.Role.HOST) View.VISIBLE else View.GONE
            if (inRoom && manager.role == WatchPartyManager.Role.HOST) {
                pinDisplay.text = manager.currentPin
            }
            refreshParticipants()
        }

        fun updateStatusDot(state: WatchPartyManager.ConnectionState) {
            val res = when (state) {
                WatchPartyManager.ConnectionState.CONNESSO ->
                    if (manager.peerPresent) android.R.drawable.presence_online
                    else android.R.drawable.presence_away
                WatchPartyManager.ConnectionState.CONNESSIONE_IN_CORSO,
                WatchPartyManager.ConnectionState.RICONNESSIONE_IN_CORSO -> android.R.drawable.presence_away
                WatchPartyManager.ConnectionState.DISCONNESSO -> android.R.drawable.presence_offline
            }
            statusDot.setBackgroundResource(res)
        }
        updateStatusDot(manager.connectionState)

        if (!PlayerAccess.isPlayerScreenActive()) {
            status.text = "Apri prima un video, poi torna qui per creare/unirti alla stanza."
        }

        manager.onStatusText = { text -> activity?.runOnUiThread { status.text = text } }
        manager.onPeerConnected = { _ -> activity?.runOnUiThread { updateStatusDot(manager.connectionState) } }
        manager.onConnectionStateChanged = { state -> activity?.runOnUiThread { updateStatusDot(state) } }
        manager.onParticipantsChanged = {
            activity?.runOnUiThread {
                refreshParticipants()
                updateStatusDot(manager.connectionState)
            }
        }

        createBtn.setOnClickListener {
            if (!PlayerAccess.isPlayerScreenActive()) {
                showToast("Apri prima un video")
                return@setOnClickListener
            }
            val pin = manager.createRoom()
            pinDisplay.text = pin
            status.text = "Condividi questo PIN con il tuo amico. Deve aprire lo stesso video."
            refreshUiForActiveRoom()
        }

        copyPinBtn.setOnClickListener {
            val pin = manager.currentPin ?: return@setOnClickListener
            val clipboard = root.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Watch Party PIN", pin))
            showToast("PIN copiato")
        }

        joinBtn.setOnClickListener {
            val pin = pinInput.text?.toString()?.trim().orEmpty()
            if (pin.length < 4) {
                showToast("Inserisci un PIN valido")
                return@setOnClickListener
            }
            if (!PlayerAccess.isPlayerScreenActive()) {
                showToast("Apri prima lo stesso video del tuo amico")
                return@setOnClickListener
            }
            manager.joinRoom(pin)
            status.text = "Connessione alla stanza $pin…"
            refreshUiForActiveRoom()
        }

        leaveBtn.setOnClickListener {
            manager.leaveRoom()
            dismiss()
        }

        resyncBtn.setOnClickListener {
            manager.requestResyncNow()
            showToast("Risincronizzazione inviata")
        }

        refreshUiForActiveRoom()

        // Riga di stato sul consenso privacy, aggiunta a runtime (root è un
        // NestedScrollView con un solo figlio diretto: si aggiunge al
        // contenitore interno, non alla radice, altrimenti crash).
        val consentLabel = TextView(root.context).apply {
            textSize = 11f
            alpha = 0.5f
            val date = WatchPartyConsent.acceptedAtLabel()
            text = if (date != null) "Termini sul relay accettati il $date"
            else "Termini sul relay non ancora accettati"
            gravity = android.view.Gravity.CENTER
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val innerContainer = (root as? ViewGroup)?.getChildAt(0) as? ViewGroup
        innerContainer?.addView(consentLabel)

        android.util.Log.d(TAG, "🏁 onCreateView() completato")
        root
    } catch (e: Exception) {
        android.util.Log.e(TAG, "💥 ECCEZIONE in onCreateView()", e)
        null
    }
}
