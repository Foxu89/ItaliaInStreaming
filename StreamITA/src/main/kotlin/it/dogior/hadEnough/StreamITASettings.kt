package it.dogior.hadEnough

import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.res.ResourcesCompat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

abstract class StreamITABaseSettingsFragment : BottomSheetDialogFragment() {

    protected val plugin: StreamITAPlugin
        get() = StreamITAPlugin.activePlugin ?: error("Plugin not available")

    protected val res
        get() = plugin.resources ?: error("Resources not available")

    protected val sharedPref: SharedPreferences?
        get() = StreamITAPlugin.activeSharedPref

    protected abstract val layoutName: String

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = res.getIdentifier(layoutName, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(layoutId), container, false)
    }

    protected fun <T : View> View.findViewByName(name: String): T? {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    protected fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(res, it, null) }
    }

    protected fun View.applyOutlineBackground() {
        this.background = getDrawable("outline")
    }

    protected fun setupSaveButton(view: View, onClick: () -> Unit) {
        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.applyOutlineBackground()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))
        saveBtn?.setOnClickListener { onClick() }
    }

    protected fun promptRestartAfterSave(message: String) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle("Riavvia applicazione")
            .setMessage(message)
            .setPositiveButton("Riavvia") { _, _ ->
                dismiss()
                restartApp()
            }
            .setNegativeButton("Più tardi", null)
            .show()
    }

    private fun restartApp() {
        val context = context?.applicationContext ?: return
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        } else {
            showToast("Impossibile riavviare automaticamente l'app. Chiudila e riaprila manualmente.")
        }
    }
}

class StreamITASettings : StreamITABaseSettingsFragment() {

    override val layoutName: String = "settings_streamita"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Build info
        val buildInfoId = res.getIdentifier("header_build_info", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        view.findViewById<TextView>(buildInfoId)?.text =
            "Ultimo aggiornamento: ${BuildConfig.BUILD_COMPLETED_AT_ROME}"

        // Setup save button - come AnimeUnity: mostra sempre popup riavvio
        setupSaveButton(view) {
            promptRestartAfterSave(
                "Impostazioni salvate. Vuoi riavviare l'applicazione ora per applicare subito le modifiche?"
            )
        }

        // Applica sfondo alle card
        listOf(
            "general_settings_card",
            "extractors_settings_card",
            "ui_settings_card",
            "advanced_settings_card"
        ).forEach { cardName ->
            view.findViewByName<View>(cardName)?.applyOutlineBackground()
        }

        // Card click listeners
        view.findViewByName<View>("general_settings_card")?.setOnClickListener {
            StreamITAGeneralSettings().show(parentFragmentManager, "GeneralSettings")
        }

        view.findViewByName<View>("extractors_settings_card")?.setOnClickListener {
            StreamITAExtractorsSettings().show(parentFragmentManager, "ExtractorsSettings")
        }

        view.findViewByName<View>("ui_settings_card")?.setOnClickListener {
            StreamITAUISettings().show(parentFragmentManager, "UISettings")
        }

        view.findViewByName<View>("advanced_settings_card")?.setOnClickListener {
            StreamITAAdvancedSettings().show(parentFragmentManager, "AdvancedSettings")
        }
    }
}

class StreamITAGeneralSettings : StreamITABaseSettingsFragment() {

    override val layoutName: String = "settings_streamita_general"

    private var currentLang: String = sharedPref?.getString(StreamITAPlugin.PREF_LANG, "it") ?: "it"
    private var currentLangPosition: Int = if (currentLang == "en") 1 else 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Applica sfondo alla card
        view.findViewByName<View>("general_options_card")?.applyOutlineBackground()

        // Setup spinner lingua
        val langs = arrayOf("it", "en")
        val langsDisplay = arrayOf("\uD83C\uDDEE\uD83C\uDDF9  Italiano", "\uD83C\uDDEC\uD83C\uDDE7  English")
        val langSpinner: Spinner? = view.findViewByName("lang_spinner")
        langSpinner?.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, langsDisplay
        )
        langSpinner?.setSelection(currentLangPosition)
        langSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentLang = langs[position]
                currentLangPosition = position
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // Setup save button
        setupSaveButton(view) {
            sharedPref?.edit {
                putString(StreamITAPlugin.PREF_LANG, currentLang)
                putInt(StreamITAPlugin.PREF_LANG_POSITION, currentLangPosition)
            }
            showToast("Modifiche in Generali salvate")
            dismiss()
        }
    }
}

class StreamITAExtractorsSettings : StreamITABaseSettingsFragment() {
    override val layoutName: String = "settings_streamita_extractors"
}

class StreamITAUISettings : StreamITABaseSettingsFragment() {
    override val layoutName: String = "settings_streamita_ui"
}

class StreamITAAdvancedSettings : StreamITABaseSettingsFragment() {
    override val layoutName: String = "settings_streamita_advanced"
}
