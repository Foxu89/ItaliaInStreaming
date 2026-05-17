package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import androidx.core.content.res.ResourcesCompat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
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

        val buildInfoId = res.getIdentifier("header_build_info", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        view.findViewById<TextView>(buildInfoId)?.text =
            "Ultimo aggiornamento: ${BuildConfig.BUILD_COMPLETED_AT_ROME}"

        setupSaveButton(view) {
            promptRestartAfterSave(
                "Impostazioni salvate. Vuoi riavviare l'applicazione ora per applicare subito le modifiche?"
            )
        }

        listOf(
            "general_settings_card",
            "extractors_settings_card",
            "ui_settings_card",
            "advanced_settings_card"
        ).forEach { cardName ->
            view.findViewByName<View>(cardName)?.applyOutlineBackground()
        }

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

    private var currentLang: String = sharedPref?.getString(StreamITAPlugin.PREF_LANG, "it-IT") ?: "it-IT"
    private var currentLangPosition: Int = sharedPref?.getInt(StreamITAPlugin.PREF_LANG_POSITION, 0) ?: 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<View>("lang_card")?.applyOutlineBackground()
        view.findViewByName<View>("cache_card")?.applyOutlineBackground()

        val langs = arrayOf("it-IT", "en-US", "es-ES", "fr-FR", "de-DE")
        val langsDisplay = arrayOf("Italiano", "English", "Espanol", "Francais", "Deutsch")
        val langSpinner: Spinner? = view.findViewByName("lang_spinner")
        langSpinner?.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, langsDisplay)
        langSpinner?.setSelection(currentLangPosition)
        langSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentLang = langs[position]
                currentLangPosition = position
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        updateCacheInfo(view)

        val clearCacheBtn: TextView? = view.findViewByName("clear_cache_btn")
        clearCacheBtn?.let { btn ->
            val dangerDrawable = getDrawable("outline_danger")
            if (dangerDrawable != null) {
                btn.background = dangerDrawable
            } else {
                btn.applyOutlineBackground()
            }
            btn.setTextColor(Color.parseColor("#FFFF7F7F"))
        }
        clearCacheBtn?.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Svuota Cache")
                .setMessage("Vuoi cancellare tutta la cache di StreamITA?")
                .setPositiveButton("Svuota") { _, _ ->
                    StreamITACache.clear()
                    updateCacheInfo(view)
                    showToast("Cache svuotata")
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        setupSaveButton(view) {
            sharedPref?.edit {
                putString(StreamITAPlugin.PREF_LANG, currentLang)
                putInt(StreamITAPlugin.PREF_LANG_POSITION, currentLangPosition)
            }
            showToast("Modifiche in Generali salvate")
            dismiss()
        }
    }

    private fun updateCacheInfo(view: View) {
        view.findViewByName<TextView>("cache_info_text")?.text = StreamITACache.stats()
    }
}

class StreamITAExtractorsSettings : StreamITABaseSettingsFragment() {
    override val layoutName: String = "settings_streamita_extractors"

    private data class ExtractorEntry(
        val key: String,
        val defaultEnabled: Boolean,
        val defaultTimeout: Int
    )

    private val extractors = listOf(
        ExtractorEntry("vixsrc", true, 15),
        ExtractorEntry("vixcloud", false, 15),
        ExtractorEntry("vidsrc", true, 15),
        ExtractorEntry("streamhg", true, 15),
        ExtractorEntry("mixdrop", true, 30),
        ExtractorEntry("dropload", true, 15),
        ExtractorEntry("cinemacity", true, 60),
        ExtractorEntry("animeunity", true, 30),
        ExtractorEntry("animeworld", true, 30),
        ExtractorEntry("animesaturn", true, 30),
    )

    private val enabledState = mutableMapOf<String, Boolean>()
    private val timeoutState = mutableMapOf<String, String>()

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        for (entry in extractors) {
            val card = view.findViewByName<View>("ext_${entry.key}_card") ?: continue
            card.applyOutlineBackground()

            val enabled = sharedPref?.getBoolean(
                StreamITAPlugin.extractorEnabledKey(entry.key), entry.defaultEnabled
            ) ?: entry.defaultEnabled
            enabledState[entry.key] = enabled

            val savedTimeout = sharedPref?.getString(
                StreamITAPlugin.extractorTimeoutKey(entry.key), null
            )
            timeoutState[entry.key] = savedTimeout ?: ""

            val switch = view.findViewByName<Switch>("ext_${entry.key}_switch")
            switch?.text = ""
            switch?.isChecked = enabled
            switch?.setOnCheckedChangeListener { _, isChecked ->
                enabledState[entry.key] = isChecked
            }

            val timeoutView = view.findViewByName<TextView>("ext_${entry.key}_timeout")
            val displayText = savedTimeout ?: entry.defaultTimeout.toString()
            timeoutView?.text = displayText
            timeoutView?.setOnClickListener {
                val ctx = context ?: return@setOnClickListener
                val input = EditText(ctx)
                input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                input.setText(timeoutState[entry.key]?.takeIf { it.isNotEmpty() } ?: entry.defaultTimeout.toString())
                input.setSelection(input.text.length)
                input.gravity = android.view.Gravity.CENTER

                AlertDialog.Builder(ctx)
                    .setTitle("Timeout - ${entry.key.replaceFirstChar { it.uppercaseChar() }}")
                    .setMessage("Inserisci il timeout in secondi")
                    .setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        val text = input.text.toString().trim()
                        if (text.isNotEmpty() && text.toIntOrNull() != null && (text.toIntOrNull() ?: 0) > 0) {
                            timeoutState[entry.key] = text
                            timeoutView.text = text
                        } else {
                            timeoutState[entry.key] = ""
                            timeoutView.text = entry.defaultTimeout.toString()
                        }
                    }
                    .setNegativeButton("Annulla", null)
                    .show()
            }
        }

        setupSaveButton(view) {
            val editor = sharedPref?.edit()
            if (editor != null) {
                for (entry in extractors) {
                    val enabled = enabledState[entry.key] ?: entry.defaultEnabled
                    editor.putBoolean(
                        StreamITAPlugin.extractorEnabledKey(entry.key), enabled
                    )

                    val timeoutStr = timeoutState[entry.key] ?: ""
                    if (timeoutStr.isNotEmpty() && timeoutStr.toIntOrNull() != null && (timeoutStr.toIntOrNull() ?: 0) > 0) {
                        editor.putString(
                            StreamITAPlugin.extractorTimeoutKey(entry.key), timeoutStr
                        )
                    } else {
                        editor.remove(StreamITAPlugin.extractorTimeoutKey(entry.key))
                    }
                }
                editor.apply()
            }
            showToast("Modifiche in Estrattori salvate")
            dismiss()
        }
    }
}

class StreamITAUISettings : StreamITABaseSettingsFragment() {
    override val layoutName: String = "settings_streamita_ui"

    private var showLogo: Boolean = sharedPref?.getBoolean(StreamITAPlugin.PREF_SHOW_LOGO, false) ?: false
    private var showRating: Boolean = sharedPref?.getBoolean(StreamITAPlugin.PREF_SHOW_RATING, false) ?: false

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<View>("ui_options_card")?.applyOutlineBackground()
        view.findViewByName<View>("show_rating_card")?.applyOutlineBackground()

        val logoSwitch: Switch? = view.findViewByName("show_logo_switch")
        logoSwitch?.text = ""
        logoSwitch?.isChecked = showLogo
        logoSwitch?.setOnCheckedChangeListener { _, isChecked ->
            showLogo = isChecked
        }

        val ratingSwitch: Switch? = view.findViewByName("show_rating_switch")
        ratingSwitch?.text = ""
        ratingSwitch?.isChecked = showRating
        ratingSwitch?.setOnCheckedChangeListener { _, isChecked ->
            showRating = isChecked
        }

        setupSaveButton(view) {
            sharedPref?.edit {
                putBoolean(StreamITAPlugin.PREF_SHOW_LOGO, showLogo)
                putBoolean(StreamITAPlugin.PREF_SHOW_RATING, showRating)
            }
            showToast("Modifiche in Interfaccia UI salvate")
            dismiss()
        }
    }
}

class StreamITAAdvancedSettings : StreamITABaseSettingsFragment() {
    override val layoutName: String = "settings_streamita_advanced"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<View>("log_card")?.applyOutlineBackground()

        val logText: TextView? = view.findViewByName("log_text")
        val logCountText: TextView? = view.findViewByName("log_count_text")
        val refreshBtn: TextView? = view.findViewByName("refresh_log_btn")
        val clearBtn: TextView? = view.findViewByName("clear_log_btn")

        fun updateLogs() {
            logText?.text = StreamITALogger.getLogs()
            logCountText?.text = "${StreamITALogger.getLogCount()} righe"
        }

        updateLogs()

        refreshBtn?.setOnClickListener {
            updateLogs()
        }

        clearBtn?.setOnClickListener {
            StreamITALogger.clear()
            updateLogs()
            showToast("Log cancellati")
        }

        setupSaveButton(view) {
            showToast("Modifiche in Avanzate salvate")
            dismiss()
        }
    }
}
