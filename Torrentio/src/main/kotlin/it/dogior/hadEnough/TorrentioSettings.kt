package it.dogior.hadEnough

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.plugins.Plugin

class TorrentioSettings(private val plugin: Plugin) : BottomSheetDialogFragment() {
    
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
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val settings = getLayout("torrentio_settings", inflater, container)
        
        val debridToggle = settings.findView<Switch>("debrid_toggle")
        debridToggle.isChecked = getKey("torrentio_debrid_enabled") == true
        debridToggle.setOnCheckedChangeListener { _, isChecked ->
            setKey("torrentio_debrid_enabled", isChecked)
        }
        
        val torboxButton = settings.findView<ImageButton>("torbox_button")
        torboxButton.setOnClickListener {
            showTorboxConfigDialog()
        }
        
        updateConnectionStatus(settings)
        
        return settings
    }
    
    private fun showTorboxConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.torbox_config, null)
        val tokenInput = dialogView.findViewById<EditText>(R.id.torbox_token)
        val enableToggle = dialogView.findViewById<Switch>(R.id.torbox_enable)
        
        tokenInput.setText(getKey("torrentio_torbox_token") ?: "")
        enableToggle.isChecked = getKey("torrentio_torbox_enabled") == true
        
        AlertDialog.Builder(requireContext())
            .setTitle("Configurazione TorBox")
            .setView(dialogView)
            .setPositiveButton("Salva") { _, _ ->
                setKey("torrentio_torbox_token", tokenInput.text.toString())
                setKey("torrentio_torbox_enabled", enableToggle.isChecked)
                showToast("Configurazione TorBox salvata")
                updateConnectionStatus(view)
                dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
    
    private fun updateConnectionStatus(root: View?) {
        val statusView = root?.findView<TextView>("debrid_status")
        val isEnabled = getKey("torrentio_torbox_enabled") == true
        val hasToken = !getKey("torrentio_torbox_token").isNullOrEmpty()
        
        val status = when {
            !isEnabled -> "❌ Disabilitato"
            !hasToken -> "⚠️ Token non configurato"
            else -> "✅ Connesso a TorBox"
        }
        statusView?.text = status
    }
}
