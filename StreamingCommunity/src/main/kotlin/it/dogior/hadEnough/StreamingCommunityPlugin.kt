package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class StreamingCommunityPlugin : Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("streamingcommunity_prefs", Context.MODE_PRIVATE)
        val language = sharedPref.getString("language", "it") ?: "it"
        
        // Registra il plugin corretto in base alla lingua
        when (language) {
            "en" -> registerMainAPI(StreamingCommunityEN())
            else -> registerMainAPI(StreamingCommunityIT())  // Default italiano
        }

        // Abilita le impostazioni (ICONA INGRANAGGIO)
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = StreamingCommunitySettings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "StreamingCommunitySettings")
        }
    }
}
