package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamITAPlugin : Plugin() {
    companion object {
        internal var activePlugin: StreamITAPlugin? = null
        internal var activeSharedPref: SharedPreferences? = null

        const val PREF_LANG = "lang"
        const val PREF_LANG_POSITION = "lang_position"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("StreamITA", Context.MODE_PRIVATE)
        activePlugin = this
        activeSharedPref = sharedPref

        registerMainAPI(StreamITA(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            activePlugin = this
            activeSharedPref = sharedPref
            StreamITASettings().show(activity.supportFragmentManager, "StreamITASettings")
        }
    }
}
