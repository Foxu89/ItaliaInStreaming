package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AltaDefinizionePlugin : Plugin() {  
    override fun load(context: Context) {
        val prefs = context.getSharedPreferences("altadefinizione_prefs", Context.MODE_PRIVATE)
        val version = prefs.getString("site_version", "v1") ?: "v1"
        
        when (version) {
            "v2" -> registerMainAPI(AltaDefinizioneV2())
            else -> registerMainAPI(AltaDefinizioneV1())
        }
    }
    
    
    override fun getSettingsView(sharedPref: SharedPreferences?): Class<*>? {
        return AltaDefinizioneSettings::class.java
    }
}
