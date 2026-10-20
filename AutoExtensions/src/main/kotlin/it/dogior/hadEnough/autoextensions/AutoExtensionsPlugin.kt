package it.dogior.hadEnough.autoextensions

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

private const val TAG = "AutoExtensions"

/**
 * CloudStream-only plugin: automatically downloads extensions from the
 * user's repositories (the ones they added, plus CloudStream's own
 * built-in list) that aren't installed yet — recreating the
 * "auto-download missing extensions" feature from Settings > Updates &
 * Backup > Extensions, in case a future CloudStream release removes it.
 *
 * Disabled by default (see AutoExtensionsPrefs): this downloads and runs
 * third-party code automatically, so it's an explicit opt-in from the
 * settings panel, not a silent default.
 */
@CloudstreamPlugin
class AutoExtensionsPlugin : Plugin() {

    private var settingsFragment: AutoExtensionsSettingsFragment? = null

    override fun load(context: Context) {
        this.openSettings = { openSettingsSheet() }
        AutoExtensionsWorker.scheduleInitialCheck(this)
    }

    /** Called by AutoExtensionsWorker on the main thread once a check finishes. */
    fun onCheckFinished(summary: String) {
        settingsFragment?.onCheckFinished(summary)
    }

    private fun openSettingsSheet() = runCatching {
        val activity = CommonActivity.activity as? FragmentActivity
        if (activity == null) {
            val msg = "Can't open AutoExtensions settings here (unsupported activity type)"
            Log.e(TAG, "openSettingsSheet(): $msg")
            showToast(msg, Toast.LENGTH_LONG)
            return@runCatching
        }
        val fragment = AutoExtensionsSettingsFragment(this)
        settingsFragment = fragment
        fragment.show(activity.supportFragmentManager, "AutoExtensions")
    }.onFailure {
        Log.e(TAG, "openSettingsSheet(): unexpected exception", it)
        showToast("Can't open AutoExtensions settings: ${it.javaClass.simpleName}", Toast.LENGTH_LONG)
    }
}
