package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import androidx.core.content.edit

class AnimeUnitySettings(
    private val plugin: AnimeUnityPlugin,
    private val sharedPref: SharedPreferences?,
) : BottomSheetDialogFragment() {
    
    // ✅ AGGIUNGI LO SWITCH LOGO
    private var currentShowLogo: Boolean = sharedPref?.getBoolean("show_logo", false) ?: false // Default: FALSE (disattivo)

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { plugin.resources?.getString(it) }
    }

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
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = "AnimeUnity Settings"
        
        val labelTw: TextView? = view.findViewByName("label")
        labelTw?.text = "Personalizza il plugin AnimeUnity"

        // ✅ AGGIUNGI LO SWITCH LOGO
        val logoSwitch: Switch? = view.findViewByName("logo_switch")
        logoSwitch?.isChecked = currentShowLogo
        logoSwitch?.setOnCheckedChangeListener { _, isChecked ->
            currentShowLogo = isChecked
        }

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        saveBtn?.setOnClickListener {
            sharedPref?.edit {
                this.clear()
                // ✅ AGGIUNGI LA PREFERENZA DEL LOGO
                this.putBoolean("show_logo", currentShowLogo)
            }
            
            AlertDialog.Builder(requireContext())
                .setTitle("Salva & Riavvia")
                .setMessage("Le impostazioni sono state salvate. Vuoi riavviare l'app per applicarle?")
                .setPositiveButton("Sì") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { _, _ ->
                    showToast("Impostazioni salvate. Riavvia manualmente per applicare.")
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
                showToast("Impossibile riavviare l'app")
            }
        } catch (e: Exception) {
            showToast("Errore nel riavvio: ${e.message}")
        }
    }
}
