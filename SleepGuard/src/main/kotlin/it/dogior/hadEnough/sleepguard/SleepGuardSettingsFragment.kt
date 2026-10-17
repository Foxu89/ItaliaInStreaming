package it.dogior.hadEnough.sleepguard

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import it.dogior.hadEnough.BuildConfig

/**
 * Pannello impostazioni SleepGuard, stesso stile "card" di Wrapped/WatchParty
 * (sfondi impostati in codice, mai android:background="@drawable/..." in XML).
 */
class SleepGuardSettingsFragment(private val plugin: SleepGuardPlugin) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? = try {
        val root = getLayout("sleepguard_settings", inflater, container)
        val ctx = requireContext()

        val timerCard = root.findView<LinearLayout>("sg_timer_card")
        val timerValue = root.findView<TextView>("sg_timer_value")
        val timerSeek = root.findView<SeekBar>("sg_timer_seek")

        val episodeCard = root.findView<LinearLayout>("sg_episode_card")
        val episodeToggle = root.findView<android.widget.Switch>("sg_episode_toggle")
        val episodeValue = root.findView<TextView>("sg_episode_value")
        val episodeSeek = root.findView<SeekBar>("sg_episode_seek")

        val volumeCard = root.findView<LinearLayout>("sg_volume_card")
        val volumeToggle = root.findView<android.widget.Switch>("sg_volume_toggle")

        val closeBtn = root.findView<TextView>("sg_close")

        timerCard.background = cardBackground()
        episodeCard.background = cardBackground()
        volumeCard.background = cardBackground()
        closeBtn.background = getDrawable("sleepguard_outline_bg") ?: coloredFallback(0x12FFFFFF, radiusDp = 16f, strokeColor = 0x33FFFFFF)

        // --- Timer di inattività ---
        val minMin = SleepGuardPrefs.MIN_INACTIVITY_MINUTES
        val maxMin = SleepGuardPrefs.MAX_INACTIVITY_MINUTES
        timerSeek.max = maxMin - minMin
        val currentMin = SleepGuardPrefs.inactivityMinutes(ctx)
        timerSeek.progress = (currentMin - minMin).coerceIn(0, timerSeek.max)
        timerValue.text = "$currentMin min"
        timerSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = minMin + progress
                timerValue.text = "$minutes min"
                if (fromUser) SleepGuardPrefs.setInactivityMinutes(ctx, minutes)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Conteggio episodi (beta) ---
        val minEp = SleepGuardPrefs.MIN_EPISODE_THRESHOLD
        val maxEp = SleepGuardPrefs.MAX_EPISODE_THRESHOLD
        episodeSeek.max = maxEp - minEp
        val currentEp = SleepGuardPrefs.episodeThreshold(ctx)
        episodeSeek.progress = (currentEp - minEp).coerceIn(0, episodeSeek.max)
        episodeValue.text = "$currentEp episodi"
        episodeToggle.isChecked = SleepGuardPrefs.isEpisodeCounterEnabled(ctx)
        fun refreshEpisodeRowEnabled() {
            val enabled = episodeToggle.isChecked
            episodeSeek.isEnabled = enabled
            episodeSeek.alpha = if (enabled) 1f else 0.4f
            episodeValue.alpha = if (enabled) 1f else 0.4f
        }
        refreshEpisodeRowEnabled()
        episodeToggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            SleepGuardPrefs.setEpisodeCounterEnabled(ctx, checked)
            refreshEpisodeRowEnabled()
        }
        episodeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val count = minEp + progress
                episodeValue.text = "$count episodi"
                if (fromUser) SleepGuardPrefs.setEpisodeThreshold(ctx, count)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // --- Reset su tasti volume/telecomando (sperimentale) ---
        volumeToggle.isChecked = SleepGuardPrefs.isVolumeKeyResetEnabled(ctx)
        volumeToggle.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            SleepGuardPrefs.setVolumeKeyResetEnabled(ctx, checked)
        }

        closeBtn.setOnClickListener { dismiss() }

        root
    } catch (t: Throwable) {
        android.util.Log.e(TAG, "Eccezione in onCreateView() delle impostazioni SleepGuard", t)
        null
    }

    /**
     * L'id del layout è valido SOLO nella tabella risorse del plugin
     * (plugin.resources): va quindi inflato con l'XmlResourceParser preso
     * da lì (getLayout), mai passando il semplice Int id a un inflater
     * legato al Context dell'host (cloneInContext) — risolverebbe l'id
     * nella tabella risorse sbagliata e l'inflate fallirebbe silenziosamente,
     * con lo stesso risultato "pannello invisibile" del bug già visto su
     * Wrapped/WatchParty. Stesso pattern esatto di WrappedFragment.getLayout.
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

    private fun cardBackground(): Drawable =
        getDrawable("sleepguard_card_bg") ?: coloredFallback(0x14FFFFFF, radiusDp = 16f, strokeColor = 0x1FFFFFFF)

    companion object {
        private const val TAG = "SleepGuardSettings"
    }
}
