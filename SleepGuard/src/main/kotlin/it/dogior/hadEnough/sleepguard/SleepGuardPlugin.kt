package it.dogior.hadEnough.sleepguard

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "SleepGuard"

/**
 * CloudStream-only plugin: detects inactivity during playback (no touch,
 * no volume/remote key, N Auto-Next episodes in a row) and pauses, asking
 * "Are you still watching?", just like Netflix/YouTube.
 *
 * Monitoring (SleepGuardWatcher) starts right away when the plugin loads,
 * not only when the settings panel is opened: it needs to be active
 * whenever the user is watching something, with no manual action required.
 * The settings panel is only there to adjust the thresholds.
 */
@CloudstreamPlugin
class SleepGuardPlugin : Plugin() {

    override fun load(context: Context) {
        this.openSettings = { openSettingsSheet() }
        SleepGuardWatcher.start(this)
    }

    private fun openSettingsSheet() = runCatching {
        val activity = CommonActivity.activity as? FragmentActivity
        if (activity == null) {
            val msg = "Can't open SleepGuard settings here (unsupported activity type)"
            Log.e(TAG, "openSettingsSheet(): $msg")
            showToast(msg, Toast.LENGTH_LONG)
            return@runCatching
        }
        SleepGuardSettingsFragment(this).show(activity.supportFragmentManager, "SleepGuard")
    }.onFailure {
        Log.e(TAG, "openSettingsSheet(): unexpected exception", it)
        showToast("Can't open SleepGuard settings: ${it.javaClass.simpleName}", Toast.LENGTH_LONG)
    }
}
