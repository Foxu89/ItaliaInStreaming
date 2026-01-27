package it.dogior.hadEnough

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamingCommunityPlugin : Plugin() {
    private var sharedPref: SharedPreferences? = null
    
    override fun load(context: Context) {
        sharedPref = context.getSharedPreferences("StreamingCommunity", Context.MODE_PRIVATE)
        val language = sharedPref?.getString("language", "it") ?: "it"
        
        when (language) {
            "en" -> registerMainAPI(StreamingCommunityEN())
            else -> registerMainAPI(StreamingCommunityIT())
        }
        
        registerExtractorAPI(VixCloudExtractor())
        registerExtractorAPI(VixSrcExtractor())

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = StreamingCommunitySettings(this, sharedPref)
            frag.show(activity.supportFragmentManager, "StreamingCommunitySettings")
        }
    }
}
