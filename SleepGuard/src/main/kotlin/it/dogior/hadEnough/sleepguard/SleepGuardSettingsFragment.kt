package it.dogior.hadEnough.sleepguard

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.StateSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import it.dogior.hadEnough.BuildConfig

/**
 * SleepGuard settings panel, same "card" style as Wrapped/WatchParty
 * (backgrounds set in code, never android:background="@drawable/..." in XML).
 */
class SleepGuardSettingsFragment(private val plugin: SleepGuardPlugin) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("sleepguard_settings", inflater, container)
        val ctx = requireContext()

        val timerCard = root.findView<LinearLayout>("sg_timer_card")
        val timerValue = root.findView<TextView>("sg_timer_value")
        val timerSeekHost = root.findView<FrameLayout>("sg_timer_seek_host")

        val episodeCard = root.findView<LinearLayout>("sg_episode_card")
        val episodeToggle = root.findView<android.widget.Switch>("sg_episode_toggle")
        val episodeValue = root.findView<TextView>("sg_episode_value")
        val episodeSeekHost = root.findView<FrameLayout>("sg_episode_seek_host")

        val volumeCard = root.findView<LinearLayout>("sg_volume_card")
        val volumeToggle = root.findView<android.widget.Switch>("sg_volume_toggle")

        val closeBtn = root.findView<TextView>("sg_close")

        timerCard.background = cardBackground()
        episodeCard.background = cardBackground()
        volumeCard.background = cardBackground()
        closeBtn.background = focusableOutlineBackground()

        // --- Inactivity timer ---
        val minMin = SleepGuardPrefs.MIN_INACTIVITY_MINUTES
        val maxMin = SleepGuardPrefs.MAX_INACTIVITY_MINUTES
        val minuteSteps = (minMin..maxMin step 5).toList()
        val currentMin = SleepGuardPrefs.inactivityMinutes(ctx)
        timerValue.text = "$currentMin min"
        mountSlider(timerSeekHost, minuteSteps, currentMin) { minutes ->
            timerValue.text = "$minutes min"
            SleepGuardPrefs.setInactivityMinutes(ctx, minutes)
        }

        // --- Episode counter (beta) ---
        val minEp = SleepGuardPrefs.MIN_EPISODE_THRESHOLD
        val maxEp = SleepGuardPrefs.MAX_EPISODE_THRESHOLD
        val episodeSteps = (minEp..maxEp).toList()
        val currentEp = SleepGuardPrefs.episodeThreshold(ctx)
        episodeValue.text = "$currentEp episodes"
        episodeToggle.isChecked = SleepGuardPrefs.isEpisodeCounterEnabled(ctx)
        fun refreshEpisodeRowEnabled() {
            val enabled = episodeToggle.isChecked
            episodeSeekHost.isEnabled = enabled
            episodeSeekHost.alpha = if (enabled) 1f else 0.4f
            episodeValue.alpha = if (enabled) 1f else 0.4f
        }
        refreshEpisodeRowEnabled()
        episodeToggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            SleepGuardPrefs.setEpisodeCounterEnabled(ctx, checked)
            refreshEpisodeRowEnabled()
        }
        mountSlider(episodeSeekHost, episodeSteps, currentEp) { count ->
            episodeValue.text = "$count episodes"
            SleepGuardPrefs.setEpisodeThreshold(ctx, count)
        }

        // --- Volume/remote key reset (experimental) ---
        volumeToggle.isChecked = SleepGuardPrefs.isVolumeKeyResetEnabled(ctx)
        volumeToggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            SleepGuardPrefs.setVolumeKeyResetEnabled(ctx, checked)
        }

        closeBtn.setOnClickListener { dismiss() }

        root
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Exception in SleepGuard settings onCreateView()", t)
        null
    }

    /**
     * The layout id is only valid in the plugin's own resource table
     * (plugin.resources): it must be inflated with the XmlResourceParser
     * taken from there (getLayout), never by passing the plain Int id to
     * an inflater bound to the host's Context (cloneInContext) — it would
     * resolve the id against the wrong resource table and inflate would
     * fail silently, with the same "invisible panel" symptom already seen
     * on Wrapped/WatchParty. Same exact pattern as WrappedFragment.getLayout.
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

    /** Visible highlight on D-pad/remote focus, same mechanism as the popup's buttons. */
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
        getDrawable("sleepguard_card_bg") ?: coloredFallback(0x14FFFFFF, radiusDp = 16f, strokeColor = 0x1FFFFFFF)

    companion object {
        private const val TAG = "SleepGuardSettings"
    }
}
