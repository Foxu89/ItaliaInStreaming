package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import java.io.File

@CloudstreamPlugin
class StreamITAPlugin : Plugin() {
    companion object {
        internal var activePlugin: StreamITAPlugin? = null
        internal var activeSharedPref: SharedPreferences? = null

        const val PREF_LANG = "lang"
        const val PREF_LANG_POSITION = "lang_position"
        const val PREF_SHOW_LOGO = "show_logo"
        const val PREF_CACHE_HOURS = "cache_hours"
        const val PREF_SHOW_RATING = "show_rating"

        fun extractorEnabledKey(name: String) = "ext_${name.lowercase()}_enabled"
        fun extractorTimeoutKey(name: String) = "ext_${name.lowercase()}_timeout"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("StreamITA", Context.MODE_PRIVATE)
        activePlugin = this
        activeSharedPref = sharedPref

        StreamITACache.setDiskDirectory(File(context.cacheDir, "streamita_cache"))

        registerMainAPI(StreamITA(sharedPref))
        reload(context)

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            activePlugin = this
            activeSharedPref = sharedPref
            StreamITASettings().show(activity.supportFragmentManager, "StreamITASettings")
        }
    }

    private fun reload(context: Context) {
        try {
            val prefs = context.getSharedPreferences("StreamITA", Context.MODE_PRIVATE)
            val addons = StreamITAStremioCatelogSettings.getAddons(prefs)
            for (addon in addons) {
                if (StreamITAStremioCatelogSettings.isEnabled(prefs, addon.name)) {
                    try {
                        registerMainAPI(StreamITAStremioCatelog(addon.url, addon.name, sharedPref = prefs))
                    } catch (_: Throwable) {
                        try { registerMainAPI(StreamITAStremioCatelog("", addon.name, sharedPref = prefs)) } catch (_: Throwable) {}
                    }
                }
            }
            try {
                MainActivity.afterPluginsLoadedEvent.invoke(true)
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            Log.e("StreamITAPlugin", "reload error ${e.message}")
        }
    }
}
