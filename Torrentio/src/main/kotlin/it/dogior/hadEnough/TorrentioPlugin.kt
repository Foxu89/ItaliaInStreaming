package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class TorrentioPlugin : Plugin() {
    
    override fun load(context: Context) {
        registerMainAPI(Torrentio())
        
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = TorrentioSettings(this)
            frag.show(activity.supportFragmentManager, "TorrentioSettings")
        }
    }
}
