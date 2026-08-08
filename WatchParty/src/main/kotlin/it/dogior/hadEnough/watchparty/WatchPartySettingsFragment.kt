package it.dogior.hadEnough.watchparty

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig // namespace del modulo, vedi build.gradle.kts root

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("watchparty_settings", inflater, container)

        val status = root.findView<TextView>("wp_status")
        val pinDisplay = root.findView<TextView>("wp_pin_display")
        val createBtn = root.findView<Button>("wp_create")
        val pinInput = root.findView<EditText>("wp_pin_input")
        val joinBtn = root.findView<Button>("wp_join")
        val leaveBtn = root.findView<Button>("wp_leave")

        fun refreshUiForActiveRoom() {
            if (manager.role != WatchPartyManager.Role.IDLE) {
                createBtn.visibility = View.GONE
                pinInput.visibility = View.GONE
                joinBtn.visibility = View.GONE
                leaveBtn.visibility = View.VISIBLE
                if (manager.role == WatchPartyManager.Role.HOST) {
                    pinDisplay.visibility = View.VISIBLE
                    pinDisplay.text = "PIN: ${manager.currentPin}"
                }
            }
        }

        if (!PlayerAccess.isPlayerScreenActive()) {
            status.text = "Apri prima un video, poi torna qui per creare/unirti alla stanza."
        }

        manager.onStatusText = { text -> activity?.runOnUiThread { status.text = text } }
        manager.onPeerConnected = { connected ->
            activity?.runOnUiThread {
                status.text = if (connected) "Amico connesso, riproduzione sincronizzata."
                else "In attesa di connessione…"
            }
        }

        createBtn.setOnClickListener {
            if (!PlayerAccess.isPlayerScreenActive()) {
                showToast("Apri prima un video")
                return@setOnClickListener
            }
            val pin = manager.createRoom()
            pinDisplay.visibility = View.VISIBLE
            pinDisplay.text = "PIN: $pin"
            status.text = "Condividi questo PIN con il tuo amico. Deve aprire lo stesso video."
            refreshUiForActiveRoom()
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

        refreshUiForActiveRoom()
        root
    } catch (e: Exception) {
        null
    }
}
