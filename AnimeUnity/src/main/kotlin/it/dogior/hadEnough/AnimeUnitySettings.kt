package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import androidx.core.content.edit

class AnimeUnitySettings(
    private val plugin: AnimeUnityPlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {
    
    private var currentShowLogo: Boolean = sharedPref?.getBoolean("show_logo", false) ?: false

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let { inflater.inflate(it, container, false) }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Header
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = "Impostazioni AnimeUnity"
        
        val labelTw: TextView? = view.findViewByName("label")
        labelTw?.text = "Personalizza il plugin AnimeUnity"

        // Switch logo
        val logoSwitch: Switch? = view.findViewByName("logo_switch")
        logoSwitch?.isChecked = currentShowLogo
        logoSwitch?.setOnCheckedChangeListener { _, isChecked ->
            currentShowLogo = isChecked
        }

        // Save button
        val saveBtn: Button? = view.findViewByName("save_btn") as? Button

        saveBtn?.setOnClickListener {
            sharedPref?.edit {
                putBoolean("show_logo", currentShowLogo)
            }
            
            AlertDialog.Builder(requireContext())
                .setTitle("Salva & Riavvia")
                .setMessage("Vuoi riavviare l'app per applicare le modifiche?")
                .setPositiveButton("Sì") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { _, _ ->
                    showToast("Salvato. Riavvia manualmente.")
                    dismiss()
                }
                .show()
        }
    }

    private fun restartApp() {
        try {
            val context = requireContext().applicationContext
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(context.packageName)
            val componentName = intent?.component

            if (componentName != null) {
                val restartIntent = Intent.makeRestartActivityTask(componentName)
                context.startActivity(restartIntent)
                Runtime.getRuntime().exit(0)
            } else {
                showToast("Impossibile riavviare")
            }
        } catch (e: Exception) {
            showToast("Errore: ${e.message}")
        }
    }
}
