package it.dogior.hadEnough

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.utils.AppUtils.toJson

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
            .setNegativeButton("Pi� tardi", null)
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

    protected fun dpToPx(dp: Int): Int {
        val density = resources?.displayMetrics?.density ?: 1f
        return (dp * density).toInt()
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

    private data class ExtractorDef(
        val key: String,
        val label: String,
        val description: String,
        val defaultEnabled: Boolean,
        val defaultTimeout: Int,
        val children: List<ExtractorDef>? = null
    )

    private val allDefs = listOf(
        ExtractorDef("vixsrc", "VixSrc", "Estrattore basato su WebView (vixsrc.to)", true, 15),
        ExtractorDef("vidsrc", "VidSrc", "Estrattore WebView (vidsrc.ru)", true, 15),
        ExtractorDef("guardahd", "Guardahd", "DropLoad, MixDrop, StreamHG", true, 15, children = listOf(
            ExtractorDef("dropload", "DropLoad", "Estrattore WebView (dr0pstream.com)", true, 15),
            ExtractorDef("mixdrop", "MixDrop", "Estrattore WebView con auto-click (mixdrop.top)", true, 30),
            ExtractorDef("streamhg", "StreamHG", "Estrattore WebView (dhcplay.com)", true, 15),
        )),
        ExtractorDef("cinemacity", "CinemaCity", "Estrattore PlayerJS (cinemacity.cc)", true, 60),
        ExtractorDef("animeunity", "AnimeUnity", "Scraper anime (animeunity.so)", true, 30),
        ExtractorDef("animeworld", "AnimeWorld", "Scraper anime (animeworld.ac)", true, 30),
        ExtractorDef("animesaturn", "AnimeSaturn", "Scraper anime (animesaturn.cx)", true, 30),
        ExtractorDef("vidxgo", "VidxGo", "Estrattore WebView (v.vidxgo.co)", true, 20),
        ExtractorDef("subtitle", "Subtitle", "Cercatore sottotitoli", true, 15),
    )

    private val defaultOrder = listOf(
        "vixsrc", "vidxgo", "cinemacity", "guardahd", "vidsrc",
        "animeunity", "animeworld", "animesaturn", "subtitle"
    )

    private val allKeys = allDefs.flatMap { def ->
        if (def.children != null) def.children.map { it.key } + def.key else listOf(def.key)
    }.toSet()

    private val currentOrder = mutableListOf<String>()
    private val enabledState = mutableMapOf<String, Boolean>()
    private val timeoutState = mutableMapOf<String, String>()
    private var currentConcurrency = 9

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadSavedState()
        initEnabledTimeoutState()

        view.findViewByName<View>("concurrency_card")?.applyOutlineBackground()
        view.findViewByName<View>("reset_order_btn")?.applyOutlineBackground()

        val concurrencyValue: TextView? = view.findViewByName("concurrency_value")
        concurrencyValue?.text = currentConcurrency.toString()

        view.findViewByName<View>("concurrency_minus")?.setOnClickListener {
            if (currentConcurrency > 1) {
                currentConcurrency--
                concurrencyValue?.text = currentConcurrency.toString()
            }
        }
        view.findViewByName<View>("concurrency_plus")?.setOnClickListener {
            if (currentConcurrency < 9) {
                currentConcurrency++
                concurrencyValue?.text = currentConcurrency.toString()
            }
        }

        view.findViewByName<View>("reset_order_btn")?.setOnClickListener {
            currentOrder.clear()
            currentOrder.addAll(defaultOrder)
            rebuildCards(view)
        }

        rebuildCards(view)

        setupSaveButton(view) { saveSettings() }
    }

    private fun loadSavedState() {
        val savedOrder = sharedPref?.getString(StreamITAPlugin.PREF_EXTRACTOR_ORDER, null)
        if (savedOrder != null) {
            try {
                val parsed = com.lagradost.cloudstream3.utils.AppUtils.parseJson<List<String>>(savedOrder)
                currentOrder.addAll(parsed.filter { it in allKeys })
            } catch (_: Exception) {}
        }
        if (currentOrder.isEmpty()) currentOrder.addAll(defaultOrder)

        currentConcurrency = sharedPref?.getInt(StreamITAPlugin.PREF_EXTRACTOR_CONCURRENCY, 9) ?: 9
    }

    private fun initEnabledTimeoutState() {
        for (def in allDefs) {
            val defsToInit = if (def.children != null) def.children else listOf(def)
            for (d in defsToInit) {
                if (d.key !in enabledState) {
                    enabledState[d.key] = sharedPref?.getBoolean(
                        StreamITAPlugin.extractorEnabledKey(d.key), d.defaultEnabled
                    ) ?: d.defaultEnabled
                    timeoutState[d.key] = sharedPref?.getString(
                        StreamITAPlugin.extractorTimeoutKey(d.key), null
                    ) ?: ""
                }
            }
        }
    }

    private fun rebuildCards(containerView: View) {
        val containerLayout: LinearLayout? = containerView.findViewByName("extractor_container")
        containerLayout?.removeAllViews()

        for ((index, orderKey) in currentOrder.withIndex()) {
            if (orderKey == "guardahd") {
                val def = allDefs.first { it.key == "guardahd" }
                containerLayout?.addView(buildGuardahdCard(containerView, def, index))
            } else {
                val def = allDefs.firstOrNull { it.key == orderKey }
                    ?: allDefs.flatMap { it.children ?: emptyList() }.firstOrNull { it.key == orderKey }
                if (def != null) {
                    containerLayout?.addView(buildNormalCard(containerView, def, index))
                }
            }
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun buildNormalCard(containerView: View, def: ExtractorDef, position: Int): View {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        val layoutId = res.getIdentifier("settings_streamita_extractor_card", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val card = inflater.inflate(res.getLayout(layoutId), containerView as ViewGroup?, false)

        card.applyOutlineBackground()

        val posView: TextView? = card.findViewByName("ext_pos")
        posView?.text = (position + 1).toString()
        posView?.applyOutlineBackground()
        posView?.setOnClickListener {
            showPositionDialog(def.key, position)
        }

        val upView: TextView? = card.findViewByName("ext_up")
        upView?.setOnClickListener { moveItem(def.key, -1); rebuildCards(requireView()) }

        val downView: TextView? = card.findViewByName("ext_down")
        downView?.setOnClickListener { moveItem(def.key, 1); rebuildCards(requireView()) }

        val labelView: TextView? = card.findViewByName("ext_label")
        labelView?.text = def.label

        val descView: TextView? = card.findViewByName("ext_description")
        descView?.text = def.description

        val switchView: Switch? = card.findViewByName("ext_switch")
        switchView?.isChecked = enabledState[def.key] ?: def.defaultEnabled
        switchView?.setOnCheckedChangeListener { _, isChecked ->
            enabledState[def.key] = isChecked
        }

        card.findViewByName<View>("ext_timeout_bg")?.applyOutlineBackground()

        val timeoutView: TextView? = card.findViewByName("ext_timeout")
        val displayText = timeoutState[def.key]?.takeIf { it.isNotEmpty() } ?: def.defaultTimeout.toString()
        timeoutView?.text = displayText
        timeoutView?.setOnClickListener {
            showTimeoutDialog(def, timeoutView)
        }

        return card
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun buildGuardahdCard(containerView: View, def: ExtractorDef, position: Int): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dpToPx(8) }
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
            setBackgroundDrawable(getDrawable("outline"))
        }

        val row = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dpToPx(52)
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
        }

        val posBadge = TextView(ctx).apply {
            text = (position + 1).toString()
            gravity = Gravity.CENTER
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            layoutParams = ViewGroup.LayoutParams(dpToPx(28), dpToPx(28))
            setBackgroundDrawable(getDrawable("outline"))
            setOnClickListener { showPositionDialog(def.key, position) }
        }
        row.addView(posBadge)

        val upView = TextView(ctx).apply {
            text = "\u25B2"
            gravity = Gravity.CENTER
            textSize = 10f
            layoutParams = ViewGroup.LayoutParams(dpToPx(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { moveItem(def.key, -1); rebuildCards(requireView()) }
        }
        row.addView(upView)

        val downView = TextView(ctx).apply {
            text = "\u25BC"
            gravity = Gravity.CENTER
            textSize = 10f
            layoutParams = ViewGroup.LayoutParams(dpToPx(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { moveItem(def.key, 1); rebuildCards(requireView()) }
        }
        row.addView(downView)

        val labelContainer = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(4), 0, 0, 0)
        }

        val label = TextView(ctx).apply {
            text = def.label
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        }
        labelContainer.addView(label)

        val desc = TextView(ctx).apply {
            text = def.description
            textSize = 11f
            alpha = 0.6f
        }
        labelContainer.addView(desc)
        row.addView(labelContainer)
        card.addView(row)

        // Sub-items for guardahd children
        val childrenContainer = LinearLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(8), 0)
        }

        def.children?.forEach { child ->
            val childRow = LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = dpToPx(44)
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
            }

            val childLabel = TextView(ctx).apply {
                text = child.label
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            childRow.addView(childLabel)

            val childSwitch = Switch(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(dpToPx(42), ViewGroup.LayoutParams.WRAP_CONTENT)
                switchMinWidth = dpToPx(28)
                text = ""
                isChecked = enabledState[child.key] ?: child.defaultEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    enabledState[child.key] = isChecked
                }
            }
            childRow.addView(childSwitch)
            childrenContainer.addView(childRow)

            // Timeout row for child
            val childTimeoutRow = LinearLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(8))
            }

            val childTimeoutLabel = TextView(ctx).apply {
                text = "Timeout (secondi)"
                textSize = 12f
                alpha = 0.6f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            childTimeoutRow.addView(childTimeoutLabel)

            val childTimeoutBg = LinearLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(dpToPx(60), dpToPx(36))
                gravity = Gravity.CENTER
                setBackgroundDrawable(getDrawable("outline"))
            }

            val childTimeout = TextView(ctx).apply {
                val displayText = timeoutState[child.key]?.takeIf { it.isNotEmpty() } ?: child.defaultTimeout.toString()
                text = displayText
                textSize = 12f
                gravity = Gravity.CENTER
                setOnClickListener { showTimeoutDialog(child, this) }
            }
            childTimeoutBg.addView(childTimeout)
            childTimeoutRow.addView(childTimeoutBg)
            childrenContainer.addView(childTimeoutRow)
        }

        card.addView(childrenContainer)
        return card
    }

    private fun moveItem(key: String, delta: Int) {
        val idx = currentOrder.indexOf(key)
        if (idx < 0) return
        val newIdx = idx + delta
        if (newIdx < 0 || newIdx >= currentOrder.size) return
        currentOrder.removeAt(idx)
        currentOrder.add(newIdx, key)
    }

    private fun showPositionDialog(key: String, currentPos: Int) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText((currentPos + 1).toString())
            setSelection(text.length)
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(ctx)
            .setTitle("Vai alla posizione")
            .setMessage("Inserisci la posizione per ${key.replaceFirstChar { it.uppercaseChar() }}")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim()
                val targetPos = text.toIntOrNull()?.minus(1) ?: return@setPositiveButton
                if (targetPos in currentOrder.indices && targetPos != currentPos) {
                    currentOrder.removeAt(currentPos)
                    currentOrder.add(targetPos, key)
                    rebuildCards(requireView())
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun showTimeoutDialog(def: ExtractorDef, timeoutView: TextView) {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(timeoutState[def.key]?.takeIf { it.isNotEmpty() } ?: def.defaultTimeout.toString())
            setSelection(text.length)
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(ctx)
            .setTitle("Timeout - ${def.label}")
            .setMessage("Inserisci il timeout in secondi")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty() && text.toIntOrNull() != null && (text.toIntOrNull() ?: 0) > 0) {
                    timeoutState[def.key] = text
                    timeoutView.text = text
                } else {
                    timeoutState[def.key] = ""
                    timeoutView.text = def.defaultTimeout.toString()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun saveSettings() {
        sharedPref?.edit {
            putString(StreamITAPlugin.PREF_EXTRACTOR_ORDER, currentOrder.toJson())
            putInt(StreamITAPlugin.PREF_EXTRACTOR_CONCURRENCY, currentConcurrency)
            for ((key, enabled) in enabledState) {
                putBoolean(StreamITAPlugin.extractorEnabledKey(key), enabled)
            }
            for ((key, timeout) in timeoutState) {
                if (timeout.isNotEmpty() && timeout.toIntOrNull() != null && (timeout.toIntOrNull() ?: 0) > 0) {
                    putString(StreamITAPlugin.extractorTimeoutKey(key), timeout)
                } else {
                    remove(StreamITAPlugin.extractorTimeoutKey(key))
                }
            }
        }
        showToast("Modifiche in Estrattori salvate")
        dismiss()
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
