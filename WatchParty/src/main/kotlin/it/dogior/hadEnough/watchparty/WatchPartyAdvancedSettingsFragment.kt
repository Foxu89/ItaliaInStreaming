package it.dogior.hadEnough.watchparty

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.BuildConfig

private const val TAG = "WatchParty"

/** Pagina dedicata aperta dalla riga "Impostazioni" del foglio principale. */
class WatchPartyAdvancedSettingsFragment(
    private val plugin: Plugin,
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

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        return if (id != 0) ResourcesCompat.getDrawable(res, id, null) else null
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("watchparty_settings_advanced", inflater, container)

        val optionsCard = root.findView<View>("wpa_options_card")
        val invisibleSwitch = root.findView<Switch>("wpa_invisible_button")

        optionsCard.background = getDrawable("outline")

        invisibleSwitch.isChecked = CloudStreamApp.getKey<String>("wp_button_invisible") == "true"
        invisibleSwitch.setOnCheckedChangeListener { _, checked ->
            CloudStreamApp.setKey("wp_button_invisible", if (checked) "true" else "false")
        }

        root
    } catch (e: Exception) {
        android.util.Log.e(TAG, "💥 ECCEZIONE in WatchPartyAdvancedSettingsFragment", e)
        null
    }
}
