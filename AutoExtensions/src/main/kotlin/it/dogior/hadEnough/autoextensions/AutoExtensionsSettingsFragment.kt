package it.dogior.hadEnough.autoextensions

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.StateSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import it.dogior.hadEnough.BuildConfig
import java.text.DateFormat
import java.util.Date

/**
 * AutoExtensions settings panel, same "card" style as Wrapped/WatchParty/
 * SleepGuard (backgrounds set in code, never android:background="@drawable/..." in XML).
 */
class AutoExtensionsSettingsFragment(private val plugin: AutoExtensionsPlugin) : BottomSheetDialogFragment() {

    private var statusText: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("autoextensions_settings", inflater, container)
        val ctx = requireContext()

        val enabledCard = root.findView<LinearLayout>("ae_enabled_card")
        val enabledToggleHost = root.findView<FrameLayout>("ae_enabled_toggle_host")

        val nsfwCard = root.findView<LinearLayout>("ae_nsfw_card")
        val nsfwToggleHost = root.findView<FrameLayout>("ae_nsfw_toggle_host")

        val updateCard = root.findView<LinearLayout>("ae_update_card")
        val updateNowBtn = root.findView<TextView>("ae_update_now")

        val enabledIcon = root.findView<ImageView>("ae_enabled_icon")
        val updateIcon = root.findView<ImageView>("ae_update_icon")

        val statusCard = root.findView<LinearLayout>("ae_status_card")
        val statusTextView = root.findView<TextView>("ae_status_text")
        val checkNowBtn = root.findView<TextView>("ae_check_now")

        val closeBtn = root.findView<TextView>("ae_close")

        enabledCard.background = cardBackground()
        nsfwCard.background = cardBackground()
        updateCard.background = cardBackground()
        statusCard.background = cardBackground()
        updateNowBtn.background = focusableOutlineBackground()
        checkNowBtn.background = focusableOutlineBackground()
        closeBtn.background = focusableOutlineBackground()

        // Same puzzle-piece icon CloudStream uses for these two settings,
        // colored like the panel's own text so it follows the theme.
        val iconColor = statusTextView.currentTextColor
        listOf(enabledIcon, updateIcon).forEach { icon ->
            getDrawable("autoextensions_ic_extension")?.let {
                icon.setImageDrawable(it)
                icon.setColorFilter(iconColor)
            } ?: run { icon.visibility = View.GONE }
        }

        statusText = statusTextView
        refreshStatusText(ctx)

        mountToggle(enabledToggleHost, AutoExtensionsPrefs.isEnabled(ctx)) { checked ->
            AutoExtensionsPrefs.setEnabled(ctx, checked)
        }
        mountToggle(nsfwToggleHost, AutoExtensionsPrefs.isNsfwIncluded(ctx)) { checked ->
            AutoExtensionsPrefs.setNsfwIncluded(ctx, checked)
        }

        checkNowBtn.setOnClickListener {
            statusTextView.text = "Checking…"
            AutoExtensionsWorker.runCheckNow()
        }
        updateNowBtn.setOnClickListener {
            statusTextView.text = "Updating…"
            AutoExtensionsWorker.runUpdateAllNow()
        }
        closeBtn.setOnClickListener { dismiss() }

        root
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Exception in AutoExtensions settings onCreateView()", t)
        null
    }

    /** Called by AutoExtensionsPlugin.onCheckFinished(), on the main thread. */
    fun onCheckFinished(summary: String) {
        val ctx = context ?: return
        statusText?.text = buildStatusText(ctx)
    }

    private fun refreshStatusText(ctx: android.content.Context) {
        statusText?.text = buildStatusText(ctx)
    }

    private fun buildStatusText(ctx: android.content.Context): String {
        val summary = AutoExtensionsPrefs.lastRunSummary(ctx) ?: return "No check has run yet."
        val at = AutoExtensionsPrefs.lastRunAt(ctx)
        val whenText = if (at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) else "?"
        return "Last check ($whenText): $summary"
    }

    /**
     * The layout id is only valid in the plugin's own resource table
     * (plugin.resources): it must be inflated with the XmlResourceParser
     * taken from there (getLayout), never by passing the plain Int id to
     * an inflater bound to the host's Context — same pattern already
     * proven on Wrapped/WatchParty/SleepGuard.
     */
    private fun getLayout(name: String, inflater: LayoutInflater, container: ViewGroup?): View {
        val id = plugin.resources!!.getIdentifier(name, "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val layout = plugin.resources!!.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    private fun <T : View> View.findView(name: String): T {
        val res = plugin.resources ?: resources
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        @Suppress("UNCHECKED_CAST")
        return findViewById<View>(id) as T
    }

    private fun getDrawable(name: String): Drawable? {
        val res = plugin.resources ?: return null
        val id = res.getIdentifier(name, "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        if (id == 0) return null
        return runCatching { ResourcesCompat.getDrawable(res, id, null) }.getOrNull()
    }

    private fun coloredFallback(fill: Int, radiusDp: Float, strokeColor: Int? = null): Drawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            strokeColor?.let { setStroke((1 * density).toInt(), it) }
        }
    }

    private fun focusableOutlineBackground(): Drawable {
        val density = resources.displayMetrics.density
        fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 16f * density
            setStroke((1 * density).toInt(), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0x24FFFFFF, 0x55FFFFFF))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0x24FFFFFF, 0x55FFFFFF))
            addState(StateSet.WILD_CARD, shape(0x12FFFFFF, 0x33FFFFFF))
        }
    }

    private fun cardBackground(): Drawable =
        getDrawable("autoextensions_card_bg") ?: coloredFallback(0x14FFFFFF, radiusDp = 16f, strokeColor = 0x1FFFFFFF)

    companion object {
        private const val TAG = "AutoExtensionsSettings"
    }
}
