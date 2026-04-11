package it.dogior.hadEnough

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import it.dogior.hadEnough.extractors.VOEExtractor

@CloudstreamPlugin
class ToonItaliaPlugin: Plugin() {
    override fun load(context: Context) {
        // Registra il main API
        registerMainAPI(ToonItalia())
        
        // Registra l'estrattore VOE
        registerExtractorAPI(VOEExtractor())
    }
}
