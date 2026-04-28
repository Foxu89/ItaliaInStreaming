package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamITAPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamITA())

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            StreamITASettings().show(activity.supportFragmentManager, "StreamITASettings")
        }
    }
}
