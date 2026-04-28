package it.dogior.hadEnough

import androidx.core.content.res.ResourcesCompat
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

abstract class StreamITABaseSettingsFragment : BottomSheetDialogFragment() {

    protected val plugin: StreamITAPlugin
        get() = StreamITAPlugin.activePlugin ?: error("Plugin not available")

    protected val res
        get() = plugin.resources ?: error("Resources not available")

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

    protected fun getStringRes(name: String): String {
        val id = res.getIdentifier(name, "string", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) res.getString(id) else ""
    }

    protected fun <T : View> View.findViewByName(name: String): T? {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return findViewById(id)
    }

    // === METODO AGGIUNTO: carica drawable dalle risorse del plugin ===
    protected fun getDrawable(name: String): Drawable? {
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return id?.let { ResourcesCompat.getDrawable(res, it, null) }
    }

    // === METODO AGGIUNTO: applica sfondo outline a una View ===
    protected fun View.applyOutlineBackground() {
        this.background = getDrawable("outline")
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

        // === APPLICA SFONDO ALLE CARD ===
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
