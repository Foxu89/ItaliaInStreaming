package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import it.dogior.hadEnough.VixSrcExtractor

@CloudstreamPlugin
class GuardaSeriePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GuardaSerie())
        registerExtractorAPI(VixSrcExtractor())
    }
}
