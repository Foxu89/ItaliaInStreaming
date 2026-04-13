package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamITAPlugin : Plugin() {
    override fun load(context: Context) {
        // Registra il provider principale
        registerMainAPI(StreamITA())
    }
}
