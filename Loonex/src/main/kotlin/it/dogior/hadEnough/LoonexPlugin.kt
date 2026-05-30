package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import it.dogior.hadEnough.extractors.LoonexExtractor

@CloudstreamPlugin
class LoonexPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Loonex())
        registerExtractorAPI(LoonexExtractor())
    }
}
