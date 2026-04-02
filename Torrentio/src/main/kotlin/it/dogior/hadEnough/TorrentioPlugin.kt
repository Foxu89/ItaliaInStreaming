package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class TorrentioPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Torrentio())
        
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity
            if (activity != null) {
                val frag = TorrentioSettings(this)
                frag.show(activity.supportFragmentManager, "TorrentioSettings")
            }
        }
    }
}
