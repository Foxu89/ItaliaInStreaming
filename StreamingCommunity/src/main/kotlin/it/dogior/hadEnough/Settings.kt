package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import androidx.core.content.edit

class Settings(
    private val plugin: StreamingCommunityPlugin,
) : BottomSheetDialogFragment() {
    private val sharedPref = plugin.sharedPref
    
    private var currentLang: String = sharedPref.getString("language", "it") ?: "it"
    private var currentLangPosition: Int = sharedPref.getInt("language_position", 0)
    private var currentShowLogo: Boolean = sharedPref.getBoolean("show_logo", false)

    @SuppressLint("DiscouragedApi")
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

        // Header
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")
        
        // Label lingua
        val labelTw: TextView? = view.findViewByName("label")
        labelTw?.text = getString("label")

        // Spinner lingua
        val langsDropdown: Spinner? = view.findViewByName("lang_spinner")
        val langs = arrayOf("it", "en")
        val langsMap = langs.map { it to getString(it) }
        langsDropdown?.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, langsMap.map { it.second }
        )
        langsDropdown?.setSelection(currentLangPosition)

        langsDropdown?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentLang = langs[position]
                currentLangPosition = position
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // Switch logo
        val logoSwitch: Switch? = view.findViewByName("logo_switch")
        logoSwitch?.isChecked = currentShowLogo
        logoSwitch?.setOnCheckedChangeListener { _, isChecked ->
            currentShowLogo = isChecked
        }

        // Bottone salva
        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.setOnClickListener {
            sharedPref.edit {
                putInt("language_position", currentLangPosition)
                putString("language", currentLang)
                putBoolean("show_logo", currentShowLogo)
            }
            
            AlertDialog.Builder(requireContext())
                .setTitle("Save & Reload")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { _, _ ->
                    showToast("Settings saved. Restart manually to apply.")
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
                showToast("Could not restart app")
            }
        } catch (e: Exception) {
            showToast("Restart error: ${e.message}")
        }
    }
}
