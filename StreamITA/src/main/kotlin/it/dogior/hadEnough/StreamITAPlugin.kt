package it.dogior.hadEnough

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamITAPlugin : Plugin() {
    companion object {
        internal var activePlugin: StreamITAPlugin? = null
    }

    override fun load(context: Context) {
        activePlugin = this
        registerMainAPI(StreamITA())

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            activePlugin = this
            StreamITASettings().show(activity.supportFragmentManager, "StreamITASettings")
        }
    }
}
