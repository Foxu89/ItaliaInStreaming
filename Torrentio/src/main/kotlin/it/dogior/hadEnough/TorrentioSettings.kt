@file:OptIn(com.lagradost.cloudstream3.Prerelease::class)

package it.dogior.hadEnough

import android.view.*
import android.widget.*
import android.os.Bundle
import android.net.Uri
import android.content.Intent
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

class TorrentioSettings(private val plugin: Plugin) : BottomSheetDialogFragment() {
    
    private fun <T : View> View.findView(name: String): T {
        val id = plugin.resources!!.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        @Suppress("UNCHECKED_CAST")
        return this.findViewById(id) as T
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
        val credsView = getLayout("torbox_config", LayoutInflater.from(requireContext()), null)
        val tokenInput = credsView.findView<EditText>("torbox_token")
        val enableToggle = credsView.findView<Switch>("torbox_enable")
        
        tokenInput.setText(getKey("torrentio_torbox_token") ?: "")
        enableToggle.isChecked = getKey("torrentio_torbox_enabled") == true
        
        AlertDialog.Builder(requireContext())
            .setTitle("Configurazione TorBox")
            .setView(credsView)
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
    
    private fun updateConnectionStatus(view: View?) {
        if (view == null) return
        val statusView = view.findView<TextView>("debrid_status")
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
